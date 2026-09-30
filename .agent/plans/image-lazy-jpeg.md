<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# Make JPEG scaling factories lazy

This ExecPlan follows `.agent/PLANS.md` and `AGENTS.md`.

## Purpose / Big Picture

`Image.getJpegBestFit` and `Image.getJpegScaled` will return an `Image` with
captured JPEG bytes, stable dimensions, and its existing path metadata before
decoding pixels. Drawing, pixel access, mutation, encoding, and other current
materialization barriers will decode from those captured bytes. Public method
descriptors, argument checks, rounding, JPEG denominator selection, and error
behavior remain compatible.

The branch is `feat/image-lazy-jpeg`, based on `origin/master` at
`0176c83c5e6a916be26f78bee0165a6ee7f07126` (fetched 2026-09-30). Work is in
`/Users/flsobral/repos/totalcross-image-lazy-jpeg`; the pre-existing dirty
worktree is left untouched.

## Working Set and Resume Protocol

This is a single-file plan with no `.agent/state` file, as requested. Read this
plan's `Progress` and active `Plan of Work` item first when resuming, then inspect
only the listed active source paths and current Git status for those paths. The
final handoff is `.agent/reports/image-lazy-jpeg.md`; read it after implementation
or when preparing the final PR summary. Test and build logs live under `/tmp` and
are summarized in the report.

## Progress

- [x] (2026-09-30) Fetched `origin`, created the isolated feature worktree from
  current `origin/master`, and inspected the JPEG factory, pipeline, source,
  materializer, native registrations, and existing tests.
- [x] (2026-09-30) Committed this plan before implementation as required.
- [x] (2026-09-30) Added the immutable pipeline-root decode policy and changed
  both Java factory return paths to retain the captured source and defer pixels.
  Added lazy metadata, denominator, signature, and file-replacement/deletion
  tests. Static diff and focused header checks pass; SDK tests wait until stale
  deployed replacements are removed so milestone validation exercises the full
  public contract.
- [ ] Remove public native replacements and registrations, then run milestone 1
  SDK tests. Continue with materialization parity/failure coverage and deployed
  macOS smokes.
- [ ] Run the specified SDK and macOS validations, write and commit the final
  report, push the branch, and open a PR against `master` without merging.

## Current Architecture and Scope

`EncodedImageSource` eagerly owns bytes plus parsed format, intrinsic/logical
dimensions, and frame metadata. On deployed targets, `captureNative` stores the
bytes in a native bag. `ImagePipeline` is the existing deferred representation;
`Image.initializeDeferred` publishes logical metadata without pixels. When an
image reaches a pixel barrier, `materializePipelineRoot` checks the source's
cached deterministic failure/backing, asks `ImageDecodeRequirement` for a
conservative JPEG denominator, and calls full or tiered decode. The private
`decodeEncodedSource`, `decodeEncodedSourceTargeted`, and
`decodeEncodedSourceTiered` methods are deploy-time native bridges.

The two public JPEG factories currently capture the source and create a smooth
scale pipeline, but then eagerly call `materializeCanonicalChecked()`. Both are
also annotated `@ReplacedByNativeOnDeploy`; their native registrations and C
handlers reopen the path and eagerly decode. Remove only those two public
replacements/registrations/handlers. Keep `nativeResizeJpeg` and the private
materialization bridges.

`ImageDecodePolicy` will be package-private, immutable, and owned by an
`ImagePipeline` root rather than `EncodedImageSource`. A request-specific policy
must not become source-global because one encoded source/backing can be reused
by separate image requests with different targets. The three modes are
`TARGET_DECODE`, `BEST_FIT`, and `EXPLICIT_RATIO`. Factory policy records the
validated inputs, captured native denominator, and exact logical output size;
ordinary pipelines keep their existing target-selection behavior.

P5 owns only JPEG factory/decode policy. Do not add backing/storage changes,
physical variants, prefetch, or PNG behavior. Do not change ordinary
`new Image(path)` semantics.

## Plan of Work

### 1. Model policy and lazy factory requests

Add the immutable package-private `ImageDecodePolicy` and carry it on the root
`ImagePipeline`. Preserve the public descriptors exactly. Build each factory
result from a captured `EncodedImageSource`, attach its request policy, set the
same path field, and return without pixel decode, full-raster allocation, or
crossing a native decoder bridge. Retain the current best-fit denominator and
`jpegScaledDimension` rounding. For explicit ratios, select and record a
conservative native denominator separately from the final logical dimensions.

Acceptance: focused SDK tests show zero decode calls and no pixel backing before
return, with width/height/frame/content-scale/path metadata available; dimensions
and public descriptors match current behavior; deleting or replacing the source
file after return does not change the materialized pixels.

### 2. Route deployed factories through Java and validate semantics

Remove public replacement annotations, NativeMethods entries/prototypes, and C
handlers for only `getJpegBestFit` and `getJpegScaled`. Keep private decode
bridges as the only native JPEG materialization acceleration. Add Java tests for
full-size and 1/2, 1/4, 1/8 eligible best-fit, arbitrary explicit ratios,
up/downscaling, progressive/color/grayscale fixtures when available, repeated
backing reuse, post-decode mutation, deterministic corrupt input caching, and
transient retry. Add feature-owned deployed smokes for lazy return/draw parity,
captured-source lifetime, cached deterministic failure, transient retry, and
public-Java/private-native routing.

Acceptance: existing barriers still materialize; no factory path reopens the
source; deterministic `ImageException` is reused; transient failures and
`OutOfMemoryError` are not permanently cached; native ABI registration has no
stale public factory entries.

### 3. Final integration and handoff

Run the focused SDK tests at milestone closure, then the specified artifact
content check and `dist -x test`. Build only the macOS ARM64 Release `tcvm` and
`Launcher` targets for deployed smokes. Rerun affected image pipeline/JPEG
regressions and P1 startup/report checks. Do not build Android, Windows, Linux,
WinCE, or iOS.

Create `.agent/reports/image-lazy-jpeg.md` last, with the requested sections and
explicit claims about API compatibility, capture/laziness, failure behavior,
deployed routing, tests, smokes, regressions, and limitations. Commit it last.
Then check the complete branch diff/status/history, push
`feat/image-lazy-jpeg`, and open a PR against `master`; do not merge it.

## Decision Log

- Decision: `ImagePipeline` root owns `ImageDecodePolicy`.
  Rationale: policy belongs to one requested result, while `EncodedImageSource`
  can share captured bytes and compatible decoded backing across requests.
  Date: 2026-09-30.
- Decision: retain the Java public factory body on deployed targets and retain
  native replacement only at private materialization-time decode bridges.
  Rationale: the public body captures bytes and creates the existing deferred
  pipeline; the bridges can still accelerate pixel decode later. Date:
  2026-09-30.

## Validation and Acceptance

- Level 1/2: run focused `ImageLazyMaterializationTest`, new JPEG policy/factory
  tests, `ImageDecodeRequirementTest`, and `EncodedImageSourceTest`, plus
  `git diff --check` and focused header validation.
- Milestone 1: run SDK tests only, covering lazy return, dimensions/rounding,
  source capture, signatures, and replacement removal.
- Milestone 2/final: run the P5 Java parity/failure tests, `artifactContentTest`,
  `dist -x test`, and the required macOS ARM64 Release `tcvm`/`Launcher`
  deployed smokes. Run affected image/JPEG and P1 configuration regressions.
- Final audit: `git diff --check origin/master...HEAD`, scoped worktree review,
  and ordered `git log --oneline --reverse origin/master..HEAD`.
- Costly platform builds outside SDK and macOS are explicitly deferred by the
  task specification. Do not benchmark; these factories change lazy timing and
  allocation behavior, but the named benchmark workloads are not established
  as JPEG factory measurements.

## Risks and Open Questions

- Explicit ratio decode selection must match the current pipeline's
  `ImageDecodeRequirement` result while preserving the exact final rounded
  dimensions. Factor the existing rule rather than approximating it.
- Existing native JPEG path handlers have platform-specific IO/error behavior.
  Verify Java capture preserves the public error contract and the native bag
  bridge decodes the already captured bytes on deployed macOS.
- A host may lack a configured macOS ARM64 `tcvm`/`Launcher` toolchain or a
  usable JPEG ImageIO reader. Record concrete failures and retain the focused
  Java proof; do not claim deployed validation without a successful smoke.
- P2 may merge while this branch is active. Before pushing/opening the PR,
  fetch and compare `origin/master`; if it advanced, rebase and resolve shared
  pipeline/native edits additively, then rerun affected P5 validation.

## Idempotence and Recovery

The feature lives in its own worktree. Do not switch or clean the original
worktree, copy its dirty files, remove its artifacts, or use destructive Git
commands. Re-run focused tests safely. Keep generated SDK/VM outputs and logs
uncommitted. If a build creates tracked-output changes, inspect scoped status
and stage only intentional sources. Before rebase, preserve the feature branch
and inspect the exact base movement; do not force-push.

## Outcomes & Retrospective

Pending implementation and validation.

## Revision Note

- Initial plan recorded before code changes on `origin/master` base.
