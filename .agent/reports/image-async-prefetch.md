<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# Async Image Preparation for ScrollContainer

## Summary

P8 adds an explicit operation for preparing visible JPEG images in a `ScrollContainer`. It captures work on the UI thread, resolves a detached copy on one worker at a time, and adopts a valid result on the UI thread. Ordinary image construction, painting, and scrolling do not start preparation.

## Public API

The only new application API is:

```java
ScrollContainer.prepareForDisplay(Runnable callback)
```

Call it on the UI thread. The optional callback runs on the UI thread once the latest batch settles. A newer call suppresses the older batch callback; it does not cancel useful preparation already in flight.

## Discovery model

`ScrollContainer` starts discovery at its scrolling bag and clips against the visible viewport and clipping ancestors. Package-private hooks in `Control` and `Container` traverse visible, positive-size controls that intersect each clip. `ImageControl` contributes the image used by its current paint path, including its temporary hardware-scaled image when active, and its painted background image. Requests use the current `Graphics` content scale.

Discovery is synchronous and does not add scroll, paint, or automatic preparation triggers. Non-JPEG or otherwise unsupported sources settle as not-prefetchable without starting a worker.

## Request identity and generations

Request equivalence uses object identity for the target `Image`, captured `EncodedImageSource`, immutable `ImagePipeline`, and effective immutable `ImageRuntimePolicy`. It also compares decode policy, exact destination-scale bits, requested physical width and height, decode denominator, current frame, pixel dimensions, logical dimensions, and a compatible readiness requirement. A path string is never source identity.

The source decoded generation identifies reusable decoded source state. A backing mutation generation, if introduced by the separately stacked P2 work, must remain an independent validity field; it is not present in this P5 base and P8 does not substitute another generation for it. The preparation batch generation controls callback delivery only and does not participate in request equivalence or invalidate useful work.

## Readiness model

The public operation requests `DRAW_READY`. An exact-scale materialized variant with a valid backing satisfies readiness. For a pipeline with no deferred operations, a compatible reusable decoded source backing also satisfies it. `COPY_READY` remains an internal readiness value for future work and does not change the public API.

## Global FIFO and scheduler

One process-global FIFO owns pending identity, active work, and a bounded useful-ready registry. At most one request is active through UI adoption. The active request is included in the 128-entry pending limit; overflow settles as transient so a later explicit call can retry. The useful-ready registry holds at most 16 entries. Equivalent pending requests attach completion accounting; equivalent valid ready work is reused.

P8 starts a per-request worker only for the FIFO head. It has no permanent worker, Semaphore, parallel decoder, or general thread pool. Queue depth and active count are synchronized by the scheduler; decode, UI adoption, and callbacks run outside its lock.

## Detached preparation

The worker deep-copies captured encoded bytes and the native encoded bag, then copies the immutable pipeline into fresh materialization caches. It resolves only that detached source and pipeline, using the captured decode policy, denominator, and scale. The live `Image` and its source are not resolved or mutated by worker code.

## UI adoption

The worker posts a result with `MainWindow.runOnMainThread`. Adoption rechecks target, source and pipeline identity, frame and dimensions, destination scale, and effective runtime policy. It then adopts the detached source backing and exact-scale variant without copying pixels.

If a compatible synchronous draw installed a newer reusable source backing during preparation, adoption keeps that backing and either reuses an exact cached variant or associates the detached exact variant with the current source generation. Otherwise the result is stale and discarded. The FIFO remains occupied until adoption reaches a terminal state.

## Failure and stale semantics

Deterministic decode failures are cached on the live source only during valid UI adoption; a later explicit request does not repeatedly decode that failure. Transient failures do not poison the source and can retry on a later explicit call. Stale request results are discarded. Superseding a batch suppresses only its callback and does not by itself make the request stale. Completion accounting settles once for ready, deduplicated, unsupported, stale, and failure outcomes.

## Diagnostics

The opt-in `PREFETCH` domain records aggregate discovered, enqueued, deduplicated, ready, stale, not-prefetchable, deterministic-failure, and transient-failure counters, plus waiting queue-depth and active-count gauges. Metric names and IDs remain internal, with no path or per-request labels. The diagnostics-off source variant compiles the bridge to no-ops.

## Stacked-branch integration

This branch is P8 stacked on `feat/image-lazy-jpeg`. [PR #479](https://github.com/TotalCross/totalcross/pull/479) targets that P5 branch. After P2/P5 integration, rebase P8 onto the updated integration target without replaying commits that have already landed, then retarget the PR to `master`.

The ordered P8 commits are:

1. `docs(image): plan async display preparation`
2. `feat(image): add async display preparation`
3. `test(image): cover async preparation lifecycle`
4. `docs(image): report async display preparation`

## Validation

The following SDK commands ran from `TotalCrossSDK` and passed:

```sh
./gradlew-agent test --tests 'totalcross.ui.image.ImageAsyncPreparationTest' \
  --tests 'totalcross.ui.ScrollContainerDisplayPreparationTest' \
  --tests 'totalcross.sys.RuntimeDiagnosticsTest' \
  --tests 'totalcross.ui.image.ImageJpegLazyFactoryTest' \
  --tests 'totalcross.ui.image.ImageDecodeRequirementTest' \
  --tests 'totalcross.ui.image.ImageDecodePolicyTest' --console=plain

./gradlew-agent test -PruntimeDiagnostics=true \
  --tests 'totalcross.ui.image.ImageAsyncPreparationTest' \
  --tests 'totalcross.ui.ScrollContainerDisplayPreparationTest' \
  --tests 'totalcross.sys.RuntimeDiagnosticsTest' \
  --tests 'tc.tools.converter.RuntimeDiagnosticsConverterTest' \
  --tests 'totalcross.ui.image.ImageJpegLazyFactoryTest' \
  --tests 'totalcross.ui.image.ImageDecodeRequirementTest' \
  --tests 'totalcross.ui.image.ImageDecodePolicyTest' \
  --tests 'totalcross.sys.runtime.RuntimeConfigurationFeatureBridgeTest' \
  --tests 'totalcross.sys.runtime.RuntimeConfigurationStartupTest' \
  --tests 'totalcross.sys.runtime.ImageRuntimeConfigurationStartupTest' \
  --tests 'totalcross.ui.ContainerClippingTest' \
  --tests 'totalcross.ui.ClippedContainerTest' \
  --tests 'totalcross.ui.ScrollContainerContentInsetsTest' --console=plain

./gradlew-agent artifactContentTest dist -x test --console=plain
./gradlew-agent compileSmokeTestJava --console=plain
```

The full diagnostics-off SDK test task also passed in the deployed P8 smoke task graph. Focused copyright-header validation passed with zero header changes, and `git diff --check origin/feat/image-lazy-jpeg...HEAD` passed.

On macOS ARM64, the Release CMake configure used local-only `QRCODEGEN_RELEASE_TAG=qrcodegen-20250123-r2` and `SQLITE3_RELEASE_TAG=sqlite3-3.32.3-r2` overrides, then `cmake --build build/p8-macos-arm64-release --target tcvm Launcher --parallel` passed. The first dependency configuration attempt could not resolve the repository-default QRCodeGen/SQLite release tags; no repository dependency pins were changed.

`runImageAsyncPreparationMacOS` passed all lifecycle checks, including deleted captured-path input, batch supersession, stale request rejection, transient retry, deterministic failure caching, UI-thread adoption, and draw reuse (`decodeBefore=0`, `decodePrepared=1`, `decodeAfterDraw=1`). `runImageLazyJpegFactoryMacOS` passed as the P5 regression smoke. Both Gradle smoke tasks received the built macOS `libtcvm.dylib` and `Launcher` through their `tcvmDylib` and `tcvmLauncher` properties.

Android, Windows, Linux, WinCE, and iOS builds were not run because this P8 validation slice targets macOS ARM64. No full benchmark was run; the smoke verifies lifecycle behavior and decode reuse rather than making a broad performance claim.

## Compatibility

The feature is explicit-only, leaves ordinary `new Image(...)` behavior unchanged, and uses P5 captured JPEG sources so source paths may be removed after capture. The source decoded generation, any future backing mutation generation, and the batch callback generation are separate validity concepts.

## Known limitations

P8 validates the macOS ARM64 native path only. P8 is based on the current P5 branch and will need a stacked rebase after P2/P5 integration. A batch that discovers more than 128 distinct pending requests settles overflow as transient; the application can call the explicit API again to retry.

## Deferred work

P9 owns Semaphore-driven process-worker scheduling. P10 owns PNG asynchronous preparation. P8 adds neither automatic preparation nor a general thread pool.
