<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# P7 Scroll Raster Reuse Report

## Summary

P7 adds conservative vertical framebuffer reuse for `ScrollContainer`. It moves
the already-painted region and repaints the newly exposed strip only when every
required surface, transform, damage, and backend fact is known to be safe.
Uncertain states keep the existing full repaint. The typed policy remains
disabled by default, and no public enable switch or P6 Image source-subrect API
was added.

The branch was created from `origin/master` at `0176c83c5e6a916be26f78bee0165a6ee7f07126`.
The final fetch on 2026-09-30 found the same `origin/master` SHA. P6 had not
merged. P7 is semantically independent of P6, but the required rendering and
native integration check remains a pre-merge gate if P6 lands first.

## Eligibility

The planner returns an internal typed fallback reason in deterministic order.
Reuse requires the typed policy to be enabled, the raster backend, `dx == 0`, a
nonzero vertical delta smaller than the viewport, a visible and bounded client
viewport, integral supported scaling, valid source pixels, a stable stride,
32-bit pixels, and no conflicting repaint or native damage. The native move is
restricted to the same physical surface and exact viewport bounds.

| State | Result |
| --- | --- |
| Policy disabled or backend is not raster | Full repaint |
| Horizontal/zero delta or delta reaches viewport height | Full repaint |
| Invalid viewport, framebuffer, stride, or source validity | Full repaint |
| Unsupported transform/scale or pixel format | Full repaint |
| Pending damage or another full-repaint condition | Full repaint |
| Native move unavailable or fails | Full repaint recovery |
| Eligible vertical delta | Move preserved pixels, then repaint exposed strip |

No workload-specific heuristic is used. The policy-enabling fixture lives in
test and smoke source sets; it installs a typed policy through a package-private
runtime integration. The fixture class is absent from the distributable SDK
JAR, and the production default remains false.

## Physical raster move

The planner converts the logical viewport to physical coordinates once at the
rendering boundary and validates dimensions and arithmetic before producing
source, destination, and exposed-strip rectangles. The move carries explicit
surface dimensions and stride and accepts only 32-bit pixels.

The native primitive is compiled only for software graphics without Skia or
GLES. It validates same-surface bounds, framebuffer length, stable dimensions,
screen transition state, and existing native dirty bounds. It takes the screen
lock before the graphics lock, copies rows with `xmemmove` in overlap-safe order,
and marks the viewport dirty for presentation. It allocates no move-sized
buffer. The Java simulator path uses row-wise `System.arraycopy`.

## Scroll integration

Reuse is attempted after the existing vertical content-position update has
succeeded. Horizontal movement retains the existing repaint behavior. On an
eligible move, `ScrollContainer` repaints the exposed strip through the normal
active-window repaint path and separately repaints a visible vertical
scrollbar. Scroll state, layout, event handling, and flick behavior remain on
the existing path.

Correctness fixtures compare both scroll directions and repeated steps against
a forced full repaint. They also compare the preserved framebuffer area to the
corresponding pre-scroll pixels. Coverage includes a delta near viewport height,
nested controls, pending damage, overlays/scrollbars, and forced move failure.

## Damage preservation

Pre-existing `Window.needsPaint`, repaint-in-progress state, overlays that cross
the viewport, and conflicting native dirty bounds prevent reuse. The native
primitive rechecks dirty bounds while holding the screen lock. If the exposed
strip repaint cannot complete cleanly, the active-window full repaint path
runs immediately. This favors a full repaint whenever the existing damage
cannot be preserved with confidence.

## Fallback and recovery

Fallback reasons remain internal: policy/backend, direction and delta,
viewport/framebuffer, transform/format, pending damage, full repaint, and native
move status. A failed move does not undo the already-applied scrollbar or bag
position. It triggers immediate full repaint recovery; diagnostics record a
recovery only when that repaint succeeds. A native implementation that is not
available also falls back safely.

## Diagnostics

`RuntimeDiagnosticSnapshot.Domain.RENDERING` records aggregate reuse attempts,
successes, fallbacks, and recovered move failures through the existing gated
diagnostics path. P7 adds no metric names or IDs to the public snapshot and
reads no diagnostic clock. Diagnostics do not affect eligibility or enable the
feature. The existing runtime configuration report continues to show the
default as disabled.

## Validation

| Check | Result |
| --- | --- |
| Focused SDK tests for planning, copy math, `ScrollContainer`, graphics scale, diagnostics, and P1 configuration parsing/startup | Passed; `TotalCrossSDK/agent-logs/20260930-222432-test-agent.log` |
| Diagnostics-enabled `RuntimeDiagnosticsTest` and `ScrollContainerRasterReuseTest` | Passed; `TotalCrossSDK/agent-logs/20260930-223804-test-agent.log` |
| `artifactContentTest` | Passed; SDK JAR contains `ScrollRasterReuse` and no test fixture class; `TotalCrossSDK/agent-logs/20260930-223809-artifactContentTest-agent.log` |
| `dist -x test` | Passed; `TotalCrossSDK/agent-logs/20260930-224356-dist-agent.log` |
| macOS ARM64 Release `tcvm` and `Launcher`, default and legacy software configurations | Passed; `agent-logs/20260930-build-macos-arm64-release-final.log` and `agent-logs/20260930-build-macos-arm64-legacy-release-final.log` |
| Deployed default/Skia macOS smoke | Passed: policy off, disabled fallback, both scroll directions, near-viewport step, and recovery matched full repaint; native reuse was rejected at the host's scale-2 surface. `nativePrimitive=false` is expected for this backend. Log: `TotalCrossSDK/agent-logs/20260930-223652-runScrollRasterReuseSmokeMacOS-full.log`. |
| Deployed legacy software macOS smoke | Passed the same framebuffer comparisons; direct positive and negative native primitive checks passed (`nativePrimitive=true`). Native scroll reuse remained rejected at scale 2. Log: `TotalCrossSDK/agent-logs/20260930-223734-runScrollRasterReuseSmokeMacOS-full.log`. |
| Focused LGPL header validation and `git diff --check` | Passed; no header changes required. `git diff --check origin/master...HEAD` is rerun after the final report commit. |

The deployed smoke summaries were:

```text
fixture=ScrollRasterReuseSmokeApp,defaultOff=true,disabledPath=true,enabledDown=true,enabledUp=true,nearViewport=true,nativeReuse=false,nativePrimitive=false,recoveredFailure=true,overallPass=true
fixture=ScrollRasterReuseSmokeApp,defaultOff=true,disabledPath=true,enabledDown=true,enabledUp=true,nearViewport=true,nativeReuse=false,nativePrimitive=true,recoveredFailure=true,overallPass=true
```

The smoke task's SDK build also completed `test`, `check`, and `dist` successfully.
No Android, Windows, Linux, WinCE, or iOS build was run, consistent with the
task's validation scope. P6 regression smokes remain deferred until P6 is
available for integration.

## Performance evidence

No timing or FPS benchmark was run. The deployed Image scroll smoke establishes
framebuffer correctness only; it does not establish a speedup. Historical
Windows measurements gathered over RDP are unstable evidence and are not a
release gate. MacOS results make no claim about Windows performance.

## Compatibility

The default remains disabled, so applications without an internal policy
rollout retain their existing repaint behavior. Reuse is vertical-only and
limited to 32-bit software raster surfaces. Skia/GLES native builds return
unavailable, and native integration at content scale greater than one falls
back because the high-density coordinate path has not been proven safe. The
implementation does not add GPU/compositor reuse, Image decoding changes, or
P6 partial-source drawing.

## Known limitations

- Native enabled scrolling was not exercised on the scale-2 macOS host; the
  feature conservatively fell back. Direct primitive behavior was exercised on
  the legacy software build.
- Transparent scrollbars and visible overlay children crossing the viewport
  select full repaint rather than attempting partial damage composition.
- Performance benefit has not been measured.

## Deferred work

Before merge, rebase onto the latest `master` if P6 has merged, resolve shared
`Graphics`/native changes additively, verify P6 and P7 remain orthogonal, and run
the relevant P6 regression smokes with the full P7 suite. Keep the PR unmerged
until that integration check is complete.
