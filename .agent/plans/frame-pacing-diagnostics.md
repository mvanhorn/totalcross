<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# P11 Frame Pacing Diagnostics

This ExecPlan follows the repository guidance supplied with this task and
`.agent/PLANS.md`. The feature branch starts at the latest fetched
`origin/master`; all work is isolated in the
`totalcross-frame-pacing-diagnostics` worktree.

## Purpose / Big Picture

Add a conservative way to compare Flick callback drivers and measure callback
work and positive lateness. TimerEvent remains the production driver at 40 fps,
with a nominal 25 ms relative timer and `Vm.getTimeStamp()` as the animation
clock. An internal UpdateListener driver and a deterministic millisecond clock
exist for tests and feature-owned measurements. Opt-in SCHEDULING diagnostics
report bounded aggregate counters and nanosecond totals. No event-loop or
production scheduling policy changes.

## Working Set and Resume Protocol

- This plan is the active progress record. Do not create `.agent/state` for
  this task.
- At continuation, read Progress, the active milestone below, and only the
  named files needed for its next action.
- `.agent/reports/frame-pacing-diagnostics.md` is the final factual handoff;
  create it after implementation, validation, and macOS measurements.
- Main implementation paths: `TotalCrossSDK/src/main/java/totalcross/ui/Flick.java`,
  the two `RuntimeDiagnosticsSupport.java` source variants, and
  `RuntimeDiagnosticSnapshot.java`.
- Test/workload paths: `TotalCrossSDK/src/test/java/totalcross/ui`,
  `TotalCrossSDK/src/test/java/totalcross/sys/RuntimeDiagnosticsTest.java`,
  and a focused app under `TotalCrossSDK/src/smokeTest/java`.
- Event-loop audit references: `MainWindow.java`, simulator
  `EventLoop.java`, native `TotalCrossVM/src/event/Event.c`, and SDL
  `event_c.h`.

## Progress

- [x] (2026-09-30) Created isolated branch `feat/frame-pacing-diagnostics`
  from fetched `origin/master`; confirmed the baseline invariants below.
- [x] (2026-09-30) Milestone 1: one shared Flick advancement path, internal
  TimerEvent/UpdateListener drivers, a deterministic clock seam, and 11 focused
  semantic tests passed. No native build was run.
- [x] (2026-09-30) Milestone 2: appended SCHEDULING aggregates, gated
  nanosecond measurements, and passed focused tests with diagnostics off/on.
- [x] (2026-09-30) Milestone 3: deployed macOS smoke passed with TimerEvent
  default/diagnostics-off baseline, two diagnostics-on runs each at TimerEvent
  40 fps, TimerEvent 60 fps, and UpdateListener, plus a diagnostics-off repeat.
  The harness reports callback, advancement, completion, work, positive
  lateness, duration, requested cadence, and final scroll position.
- [ ] Milestone 4: focused SDK/native validation, audit report, final handoff,
  ordered commits, push, and pull request against `master`.
  Diagnostics-off `dist -x test`, diagnostics-on `dist -x test`, and
  `artifactContentTest -x test` pass. An unexcluded artifact run invoked the
  full test suite and hit a suite-order `SlidingWindowSafeAreaTest` null-array
  failure (440 completed, 1 failed, 12 skipped); that test passes in isolation.
  Focused Flick, ScrollContainer, diagnostics, and converter suites pass with
  diagnostics off and on. Release macOS ARM64 `tcvm` and `Launcher` builds and
  the deployed P11 smoke pass. The baseline has no separate Flick/
  ScrollContainer deployed smoke or standalone MainWindow TimerEvent/
  UpdateListener tests; the P11 workload and focused tests cover these paths.

## Current Architecture and Scope

The fetched master baseline has `Flick.defaultFrameRate == 40`; Flick implements
`TimerListener`, starts its animation with
`addTimer(timer, 1000 / frameRate)`, and computes animation elapsed time from
`Vm.getTimeStamp() - t0`. Its timer callback currently owns the motion
equation, scroll limiting, listener and target scrolling, completion, page
position update, stop behavior, and `e.consumed = true`.

`MainWindow` already dispatches UpdateListeners before traversing TimerEvents.
UpdateListeners are weak references and have explicit add/remove methods. The
timer traversal uses relative `lastTick` updates and wrap handling; the update
dispatch is gated by `Settings.minimalUpdateInterval`. Keep this machinery and
ordering unchanged.

RuntimeDiagnostics already has a public RUNTIME domain and opt-in/compile-out
Java implementations. Metric IDs are internal and snapshots expose only domain
and kind totals. Append SCHEDULING without reordering existing domain values;
keep new metric IDs and names private. The SDK already maps deployed
`System.nanoTime()` to the native `getNanoTime()` bridge.

The bounded pacing audit found:

- `TotalCrossVM/src/event/Event.c:pumpEvent` polls the selected native backend,
  checks the VM timer, then calls `Sleep(1)` except on iOS to avoid a tight
  event loop. `isEventAvailable` also sleeps 1 ms before polling.
- `TotalCrossVM/src/event/sdl/event_c.h:privateIsEventAvailable` uses
  nonblocking `SDL_PollEvent(NULL)`; no SDL delay or blocking wait is added.
- `TotalCrossSDK/src/main/java/tc/simulator/EventLoop.java` polls its queue for
  up to 5 ms and runs at maximum thread priority. This bounds simulator event
  wakeups.
- Simulator `RuntimeState` and `StreamBridge` use `Thread.yield()` for
  startup/thread cooperation and worker responsiveness. Leave them unchanged.

These waits can influence when callbacks arrive; P11 measures that effect and
does not alter it. Platform-specific event sources continue to be polled by the
existing native backend.

## Plan of Work

### Milestone 1 — behavioral parity

Refactor the existing callback body into one authoritative
`advanceAnimation()`. TimerEvent remains a thin adapter that validates its
timer, preserves UIRobot abort behavior and consumed state, and invokes the
shared body. UpdateListener invokes the same body and does not use its elapsed
argument as animation time.

Add exactly two internal modes, `TIMER_EVENT` and `UPDATE_LISTENER`. The
public constructor selects TimerEvent; a package-private seam selects the
alternate mode for tests and the P11 workload. Register/unregister UpdateListener
with the same MainWindow at start/stop. Do not arm both drivers.

Add a package-private nullable test clock. Production reads stay direct through
`Vm.getTimeStamp()`; the injected clock is used only in deterministic tests.
Retain integer milliseconds and all rounding behavior.

Tests compare same-sample sequences across both drivers for vertical and
horizontal motion, distance limits, refused scroll, time-based completion,
page position, listener deltas, stop/pen-down behavior, UIRobot abort, and
repeated driver start/stop registration. Complete this milestone before any
native build.

### Milestone 2 — diagnostics

Append the SCHEDULING domain and add hidden aggregate Flick callback,
advancement, and completion counters plus cumulative advancement-work and
positive-lateness timers. Follow the existing default-off and compile-out
patterns; preserve RUNTIME metrics and reset/delta behavior.

Check the diagnostics gate before every `System.nanoTime()` read. Measure only
the shared advancement body, including synchronous scroll/listener work. For
TimerEvent lateness use the requested integer interval
`(1000 / frameRate) * 1_000_000` ns and a diagnostic-only expected timestamp.
For UpdateListener observations use the harness cadence label; this is not a
production timing guarantee. Do not schedule callbacks from expected time or
add per-frame production samples.

Cover disabled/no-change, enabled metric increments, nonnegative lateness,
timer deltas/reset, unrelated-domain isolation, and the default-off path.

### Milestone 3 — measurement and deployed smoke

Add one focused harness reporting callback and advancement counts, animation
duration, total/average advancement work, total/average/max positive lateness
(test-side max only), final scroll position, completion, driver, and requested
cadence. Keep samples bounded to test/harness code.

Add a durable macOS smoke for default TimerEvent/40 fps, equivalent UpdateListener
completion, enabled nonzero diagnostics, and disabled identical final position.
Use semantic assertions only; timing output is evidence, not an FPS threshold.
Run economical repetitions for TimerEvent 40 fps, TimerEvent 60 fps, and
UpdateListener, and label integer cadence accurately: 25 ms at 40 fps and 16 ms
at 60 fps.

### Milestone 4 — validation and handoff

Run focused SDK tests, artifact-boundary validation, diagnostics-off
`artifactContentTest` and `dist -x test`, focused tests with
`-PruntimeDiagnostics=true`, then the allowed macOS ARM64 Release `tcvm` and
`Launcher` builds and deployed P11 smoke. Rerun affected basic Flick and
ScrollContainer smokes. Do not build Android, Windows, Linux, WinCE, or iOS.

Create the final report last, commit it last, push the feature branch, and open
a PR against `master` without merging. Report measurements as macOS evidence,
not a Windows guarantee.

## Decision Log

- Keep TimerEvent as the default and retain 40 fps / 25 ms nominal cadence.
  Rationale: P11 is diagnostic and must not change application pacing.
- Keep `Vm.getTimeStamp()` and integer milliseconds for all animation progress;
  use `System.nanoTime()` only behind the enabled diagnostics gate.
- Share the entire advancement body between both drivers; do not duplicate the
  motion equation.
- Treat expected callback time as measurement state only. Do not affect timer
  scheduling, `lastTick`, catch-up, or event-loop waits.
- Store metric keys privately and append the public SCHEDULING domain. Keep
  histogram, percentile, trace, and frame-array data out of production.
- Bridge Flick to RuntimeDiagnostics through narrowly scoped internal hooks;
  snapshots still expose only domain/kind totals, with no metric IDs or keys.
- Keep the final report as the only editorial handoff; do not add a state file.

## Validation and Acceptance

Use the smallest sufficient checks at each slice, then the explicit milestone
checks above:

1. Semantic parity tests pass before any native build.
2. Focused diagnostics tests prove disabled behavior and enabled bounded
   aggregates in both SDK variants.
3. Workload and macOS smoke prove lifecycle and semantic equivalence without
   flaky timing thresholds.
4. Final focused SDK checks, allowed macOS ARM64 Release build/smoke, artifact
   boundary checks, copyright validation for changed first-party files, and
   `git diff --check origin/master...HEAD` pass.

At completion verify default frame rate/driver/interval, production millisecond
clock, gated nanoTime, unchanged relative timer/update/event-loop behavior,
unchanged SDL polling and sleep/yield calls, no public pacing mode, no
absolute-deadline scheduler, no production percentiles/histograms/traces, no
`.agent/state`, and new-file size limits from the task.

## Risks and Open Questions

- Diagnostic update-driver cadence must be labeled as an observation setting,
  since MainWindow UpdateListener callbacks are governed by the existing update
  interval gate.
- Tests need to control UI event registration without changing MainWindow
  dispatch ordering. Prefer existing package-level access and a deterministic
  fixture; change MainWindow only if a minimal test seam is essential.
- Flick is in `totalcross.ui` while diagnostic storage is in
  `totalcross.sys`. Any recording bridge must remain narrow, avoid public
  metric names/IDs, and add no pacing configuration surface.

## Idempotence and Recovery

The separate worktree protects the active image-scroll branch and its local
files. Retry work only within `/Users/flsobral/repos/totalcross-frame-pacing-diagnostics`.
Do not copy unrelated generated artifacts or user files into this branch. The
first branch commit is this plan; implementation starts only after that commit.
Never reset or clean generated/user data. The final report contains facts and
limitations, not commit IDs, raw logs, or an execution diary.

## Outcomes & Retrospective

Milestone 1 routes both internal drivers through the same millisecond-based
advancement body. The parity suite passes for both axes, scroll limits, target
refusal, elapsed completion, page position, listener deltas, UIRobot abort, and
driver cleanup. Milestone 2 adds bounded, opt-in SCHEDULING aggregates and
passes focused tests in both SDK variants. TimerEvent remains the default;
native builds remain deferred until the final milestone.

## Revision Note

Initial plan for P11. The baseline confirms that one callback body can preserve
current motion behavior while adding a test-only alternate driver.
