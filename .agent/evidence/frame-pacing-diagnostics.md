<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# P11 Frame Pacing Diagnostics Evidence Index

Validation and measurement evidence for the final report. Full logs stay in
their task output locations; this index records paths and compact results.

## SDK validation

- Focused suites passed with diagnostics off and on:
  `FlickPacingTest`, `ScrollContainerContentInsetsTest`,
  `RuntimeDiagnosticsTest`, and `RuntimeDiagnosticsConverterTest`.
  Enabled-variant XML: 11/11 Flick, 5/5 ScrollContainer, 13 passed/1 skipped
  RuntimeDiagnostics, and 5/5 converter tests. Logs:
  `TotalCrossSDK/agent-logs/20260930-224230-test-full.log`,
  `TotalCrossSDK/agent-logs/20260930-224232-test-full.log`.
- `artifactContentTest -x test`: 11 passed, 0 skipped, 0 failures.
  Log: `TotalCrossSDK/agent-logs/20260930-223453-artifactContentTest-full.log`.
- An unexcluded `artifactContentTest` invoked the broad `:test` task, which
  reported 440 tests completed, 1 failure, and 12 skipped. The failure was
  `SlidingWindowSafeAreaTest.popupDismissAndReopenPreserveSynchronousWindowStackSemantics`
  at line 148 because the saved window-stack array was null. Running that test
  alone passed. Logs:
  `TotalCrossSDK/agent-logs/20260930-223324-artifactContentTest-full.log`,
  `TotalCrossSDK/agent-logs/20260930-224548-test-full.log`.
- Diagnostics-off `dist -x test` passed. Log:
  `TotalCrossSDK/agent-logs/20260930-223504-dist-full.log`.
- Diagnostics-on `dist -x test` passed. Log:
  `TotalCrossSDK/agent-logs/20260930-223950-dist-full.log`.
- `compileFlickPacingDiagnosticsSmoke` with diagnostics on passed. Log:
  `TotalCrossSDK/agent-logs/20260930-223547-compileFlickPacingDiagnosticsSmoke-full.log`.
- Changed-source copyright validation and `git diff --check` passed at each
  checkpoint.

## Native and deployed smoke

- CMake configured Release for macOS ARM64 with runtime diagnostics enabled;
  configure completed. Log: `/tmp/p11-cmake-configure.log`.
- Ninja built both `tcvm` and `Launcher` for arm64: 126/126 build steps,
  successful. Log: `/tmp/p11-ninja-macos.log`.
- The deployed P11 smoke passed on macOS ARM64. Log:
  `TotalCrossSDK/agent-logs/20260930-224024-runFlickPacingDiagnosticsSmokeMacOS-full.log`.
- The deployed baseline and diagnostics-off repeat both completed at final
  scroll position 0 with default TimerEvent/40 fps (nominal 25 ms).
- Diagnostics-on run aggregates:

| Driver | Repetition | Callbacks / advancements | Duration | Average work | Average positive lateness | Max positive lateness | Final position |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| TimerEvent 40 fps, 25 ms | 1 | 3 / 3 | 76 ms | 5.583 µs | 0.540 ms | 0.745 ms | 0 |
| TimerEvent 40 fps, 25 ms | 2 | 3 / 3 | 75 ms | 4.527 µs | 0.294 ms | 0.535 ms | 0 |
| TimerEvent 60 fps, 16 ms | 1 | 4 / 4 | 64 ms | 3.760 µs | 0.417 ms | 0.830 ms | 0 |
| TimerEvent 60 fps, 16 ms | 2 | 4 / 4 | 66 ms | 3.614 µs | 1.204 ms | 1.949 ms | 0 |
| UpdateListener observation, selected 25 ms | 1 | 4 / 4 | 65 ms | 4.760 µs | 0 ms | 0 ms | 0 |
| UpdateListener observation, selected 25 ms | 2 | 4 / 4 | 65 ms | 3.823 µs | 0 ms | 0 ms | 0 |

All six diagnostics-on runs completed once. UpdateListener zero positive
lateness means callbacks arrived before the selected 25 ms expected cadence in
these short runs; it is not a production scheduling guarantee.

## Build outputs left local

The smoke deploy produced `TotalCrossSDK/IOSDateFixture.tcz` and
`TotalCrossSDK/etc/launchers/macos/Launcher` as untracked local artifacts. They
were left untouched and excluded from commits. Native build outputs remain in
the ignored `build/p11-macos-arm64` directory.
