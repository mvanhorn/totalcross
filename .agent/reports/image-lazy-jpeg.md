<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# P5 Lazy JPEG Factories

## Summary

`Image.getJpegBestFit(...)` and `Image.getJpegScaled(...)` now return deferred
images. Each factory captures the encoded JPEG and its metadata before returning;
pixel decode occurs later when an existing image operation needs pixels.

## Public compatibility

Both public method descriptors and declared exceptions remain unchanged. The
factories preserve input validation, rounded logical dimensions, content-scale
metadata, and the diagnostic `path` field. Ordinary `new Image(path)` behavior
is unchanged.

## Decode policy

Package-private immutable `ImageDecodePolicy` belongs to the root
`ImagePipeline`, because the policy describes one requested result while an
encoded source may be shared by several requests. It has the semantic modes
`TARGET_DECODE`, `BEST_FIT`, and `EXPLICIT_RATIO`.

Best-fit requests retain their requested target and selected JPEG denominator.
Explicit-ratio requests retain the validated numerator and denominator, exact
rounded output dimensions, and the conservative native decode denominator as
separate values. Existing tier selection and final scaling rules are preserved.

## Source capture and lifetime

Factories read the path once into an owned `EncodedImageSource`, parse the JPEG
format and intrinsic dimensions, and close the input before returning. The
returned image keeps the original path for diagnostics but keeps no live file or
stream. Tests replace the same path between factory calls, replace it again
before materialization, and delete it after a factory call; results continue to
match the bytes captured by each request. Neither materialization path reopens
the path.

## Materialization behavior

The factories use the existing `ImagePipeline` and its pixel barriers. Best-fit
and explicit-ratio policies choose the captured conservative JPEG tier, then
apply only the remaining scaling needed to produce the exact logical output.
Compatible decoded backing reuse and opaque JPEG pixels continue through the
existing source and backing contracts. No transformed-variant cache was added.

## Failure semantics

Deterministic corrupt JPEG failures are cached on the encoded source and reused
by repeated materialization. Transient decoder/native infrastructure failures
are not cached and a later access retries successfully. The existing behavior
that keeps `OutOfMemoryError` outside deterministic failure caching is
preserved.

## Native/deployed integration

The public factories no longer use `@ReplacedByNativeOnDeploy`. Their stale
native declarations, prototypes, address-table entries, and path-reading C
handlers were removed. Private targeted/tiered materialization bridges and
`nativeResizeJpeg` remain. Native ABI tests verify that the converted public
methods retain executable Java code. A deployed macOS smoke confirmed the Java
factory route and private native materialization route.

## Validation

- Focused SDK image/JPEG/ABI/P1 regressions: 152 tests in 20 suites, 0 failures,
  0 errors, 0 skipped (`/tmp/image-lazy-jpeg-regressions.log`).
- SDK test task run through the deployed smoke build: 440 tests in 89 suites,
  0 failures, 0 errors, 8 skipped.
- `artifactContentTest`: 11 tests passed
  (`/tmp/image-lazy-jpeg-artifacts.log`).
- `dist -x test`: passed (`/tmp/image-lazy-jpeg-dist.log`).
- Copyright headers for seven changed/new files: passed.
- macOS ARM64 Release `tcvm` and `Launcher`: built successfully. The configure
  used the depot manifest's QRCodeGen and SQLite3 release tags because the
  CMake helper defaults referenced older tags. Artifacts are in
  `/tmp/image-lazy-jpeg-macos-arm64-release/`.
- `ImageLazyJpegFactorySmokeApp`: passed lazy return, later draw, captured-byte
  parity after replacement/deletion, deterministic failure caching, transient
  retry, Java factory routing, and private native decode checks
  (`/tmp/image-lazy-jpeg-factory-macos-parity-final.log`).
- JPEG pinch and modifier smokes passed, along with all six image runtime
  configuration macOS smokes
  (`/tmp/image-lazy-jpeg-related-macos-smokes.log`).
- `git diff --check origin/master...HEAD`: passed before this report was added;
  it was rerun after committing the report.

## Compatibility

Public signatures and exception declarations are unchanged, and public factory
methods execute their Java deferred pipeline on deployed runtimes. Encoded
bytes and required metadata are captured eagerly; JPEG pixels are decoded
lazily. Deterministic failures are cached, transient failures retry, and the
original path is never reopened after return.

## Known limitations

Validation covered the SDK and macOS ARM64 deployment only. The macOS build
needed explicit release-tag overrides matching the fetched depot-tools
manifest; no dependency source or release metadata was changed in this branch.

## Deferred work

P5 does not add raster backing changes, physical variants, compact storage,
asynchronous prefetch, PNG prefetch, or a new diagnostics domain. Android,
Windows, Linux, WinCE, and iOS builds remain outside this validation scope.
