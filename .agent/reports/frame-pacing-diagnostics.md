<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# P11 Frame Pacing Diagnostics

## Summary

P11 adds an internal UpdateListener driver for Flick, deterministic millisecond
clock injection for tests, and bounded opt-in SCHEDULING aggregates. TimerEvent
remains the production driver. No event-loop or native polling behavior changed.

## Final Flick advancement architecture

TimerEvent and UpdateListener callbacks route through one `advanceAnimation()`
method. It reads the existing millisecond animation clock, applies the existing
motion equation and scroll limit, sends the same deltas to listeners and the
target, updates page position, and stops when the existing completion conditions
are met.

The TimerEvent adapter accepts only Flick's active timer, preserves UIRobot abort
behavior, invokes the shared method, and consumes the event. The UpdateListener
adapter invokes the same method and ignores `elapsedMilliseconds` for motion.

## Driver semantics

The public constructor selects TimerEvent. That driver registers
`addTimer(timer, 1000 / frameRate)` and removes that timer at stop. The internal
UpdateListener driver registers with the current MainWindow and does not arm
Flick's TimerEvent. Stop removes the listener from the MainWindow captured at
start. Focused tests cover callback parity, driver cleanup, repeated
start/stop, and the absence of double advancement.

## Production defaults

`Flick.defaultFrameRate` remains 40. TimerEvent remains the default, with its
nominal 25 ms relative interval. The 60 fps workload uses the existing integer
interval of 16 ms. P11 adds no public pacing selector or global performance
flag.

## Diagnostics

The appended SCHEDULING domain contains five private aggregates: Flick callback
count, advancement count, completion count, cumulative advancement work in
nanoseconds, and cumulative positive callback lateness in nanoseconds. Snapshots
continue to expose only domain/kind totals. Maximum lateness is collected only
in the test workload; production diagnostics contain no frame samples,
histograms, percentiles, or traces.

## Clock semantics

Production drag timing and animation position continue to use
`Vm.getTimeStamp()`. The injected clock is package-private and used by focused
tests. `System.nanoTime()` runs only while SCHEDULING diagnostics are enabled:
to reset expected callback timing, measure callback lateness, and time the
shared advancement call. The compile-out SDK path reports the gate as disabled.
These measurements do not feed the motion equation, completion checks, or timer
scheduling.

## Sleep/polling audit

- `TotalCrossVM/src/event/Event.c` sleeps 1 ms in `pumpEvent` except on iOS and
  in `isEventAvailable`, reducing tight polling.
- `TotalCrossVM/src/event/sdl/event_c.h` checks and consumes events with
  nonblocking `SDL_PollEvent`; this path adds no SDL delay or blocking wait.
- The simulator `EventLoop` waits up to 5 ms on its event queue and runs at
  maximum thread priority.
- `MainWindow.runOnMainThread` schedules a 1 ms timer and yields for thread
  cooperation. Simulator `RuntimeState` also yields during startup; `StreamBridge`
  sleeps 10 ms while a modal alert remains visible and yields in its vibration
  worker.

These existing waits can affect callback arrival. P11 leaves them unchanged,
along with MainWindow timer traversal, relative `lastTick` scheduling, and
UpdateListener dispatch ordering.

## Deterministic validation

The focused Flick suite passes for vertical and horizontal motion, identical
driver deltas, scroll limiting, page position, target refusal, elapsed-time
completion, listener delivery, TimerEvent abort behavior, pen-down stop,
registration cleanup, repeated lifecycle, 40/60 cadence, and diagnostics.
The diagnostics tests cover default-off behavior, aggregate updates, reset and
delta behavior, and compile-out gates. The available ScrollContainer test also
passes.

The selected Flick, ScrollContainer, RuntimeDiagnostics, and converter suites
pass with diagnostics off and with `-PruntimeDiagnostics=true`. The
artifact-boundary task passes 11 checks when run as
`artifactContentTest -x test`; diagnostics-off `dist -x test` and
diagnostics-on `dist -x test` both pass.

The Release macOS ARM64 CMake/Ninja build produced both `tcvm` and `Launcher`
with runtime diagnostics enabled. The deployed smoke used those matching
artifacts. Copyright-header validation and the final diff check pass.

The unexcluded artifact task also invoked the general `:test` task. That run
reported 440 tests completed, 1 failure, and 12 skipped. The failure was
`SlidingWindowSafeAreaTest.popupDismissAndReopenPreserveSynchronousWindowStackSemantics`
at line 148, where the saved window-stack array was null. Running that test
alone passed, consistent with an existing shared-state/order issue. The test
source is unchanged. No separate baseline Flick/ScrollContainer deployed smoke
or standalone MainWindow TimerEvent/UpdateListener test exists in this checkout;
the P11 smoke and focused adapter tests cover those paths.

## macOS measurement evidence

The deployed macOS ARM64 smoke passed. It ran a default TimerEvent baseline, two
diagnostics-on runs for each requested driver/cadence, and a diagnostics-off
repeat. Every run completed once with final scroll position 0. The measurements
are short macOS observations, not FPS thresholds or Windows performance
predictions.

| Driver and selected cadence | Callbacks per run | Duration | Average advancement work | Average positive lateness |
| --- | ---: | ---: | ---: | ---: |
| TimerEvent, 40 fps / 25 ms | 3 | 75–76 ms | 4.527–5.583 µs | 0.294–0.540 ms |
| TimerEvent, 60 fps / 16 ms | 4 | 64–66 ms | 3.614–3.760 µs | 0.417–1.204 ms |
| UpdateListener observation, selected 25 ms | 4 | 65 ms | 3.823–4.760 µs | 0 ms |

The UpdateListener callback observations arrived before the selected 25 ms
expected cadence in both short runs, so their positive lateness was zero. This
does not make UpdateListener a production pacing guarantee.

## Compatibility

Applications that make no new calls keep using TimerEvent at 40 fps and the
same millisecond animation clock. The new driver and clock seam are internal.
Runtime configuration reporting and MainWindow scheduling remain unchanged.

No P11-specific `.agent/state` file was created. This worktree contained 13
pre-existing unrelated state files, all left untouched. New P11 files remain
within the repository's size guidance.

## Known limitations

The measurement workload exercises a short, controlled scroll and uses the
existing MainWindow update cadence for UpdateListener observations. It does not
characterize sustained user interaction, display refresh alignment, or other
platforms. The broad test run retains the isolated shared-state failure
described above.

## Deferred experiments

Absolute deadlines, catch-up scheduling, changing sleep/yield behavior, SDL
polling changes, native blocking waits, refresh/vsync pacing, and production
percentile or trace diagnostics remain outside P11.
