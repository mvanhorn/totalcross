<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# Async Image Preparation for ScrollContainer

This ExecPlan follows `.agent/PLANS.md` and `AGENTS.md`.

## Purpose / Big Picture

Applications can explicitly ask a `ScrollContainer` to prepare currently visible JPEG-backed `ImageControl`s before drawing. Discovery and adoption stay on the UI thread. JPEG decode and pipeline materialization run on a detached image snapshot on a worker. The next draw reuses the adopted prepared variant. Normal scrolling remains unchanged and automatic preparation remains disabled.

## Working Set and Resume Protocol

This plan is the active implementation record. Per the task brief, do not create `.agent/state`. Keep validation output in task-specific logs under `TotalCrossSDK/build` or `/tmp`; summarize results here at milestone checkpoints. At a continuation, read this plan's Progress and active milestone, then inspect only that milestone's source paths.

The source of truth for the feature contract is the user-provided `Q-P8-async-image-preparation.md`. P5 is the parent layer; this branch was created from the fetched `origin/feat/image-lazy-jpeg` head. Do not hardcode the P5 revision in future rebase instructions.

## Progress

- [x] (2026-09-30) Created `feat/image-async-prefetch` in an isolated worktree from fetched P5 head `b5a12be39`; verified lazy JPEG factories, typed decode policy, captured encoded source, decoded generation, and drawing resolution are present.
- [ ] Identity and discovery: add UI hierarchy hooks, visible clip discovery, immutable Image request snapshot, readiness, and the sole public ScrollContainer operation.
- [ ] Detached preparation and adoption: JPEG worker preparation, process-global FIFO/dedupe, stale and failure handling, UI callbacks.
- [ ] Diagnostics and tests: aggregate PREFETCH metrics, focused lifecycle coverage, artifact boundary and deployed macOS smokes.
- [ ] Finalize validation, editorial report, ordered commits, push, and stacked PR targeting P5.

## Current Architecture and Scope

`Image` has a deferred `ImagePipeline`; pipeline nodes and their dimensions/operations are immutable, while each node's two materialized-variant slots are mutable. `Image.resolveForDrawing(scale)` checks those slots, resolves the chain, and warms them. For encoded roots, resolution uses `EncodedImageSource`'s reusable backing, decoded denominator, decoded generation, and deterministic failure cache. A worker must therefore resolve a private pipeline/source copy, then transfer a ready result on the UI thread; it must never resolve the live pipeline.

`ScrollContainer` owns a `ClippedContainer` named `bag`, nested below `bag0`. Its public `getClientRect()` describes the scroll viewport. `Container.children` is a linked list. `ImageControl` paints `img` (or `img0` while temporary hardware scaling is active) and may paint `imgBack`; its own hook can identify the image actually used without making ScrollContainer image-aware. `Control.getAbsoluteRect()` gives MainWindow-relative geometry. Container child clipping defaults to its bounds and is represented by `clipsChildrenToBounds()`.

The P1 `ImageRuntimePolicy` is immutable and exposed by `ImageRuntimeConfigurationStartup.currentPolicy()`. `imagePreparation().automaticPreparation()` defaults false. P5 does not currently contain a PREFETCH diagnostic domain or the feature metric bridge described by the task brief; add that capability behind the existing compile-out diagnostics variants without publishing metric keys.

## Plan of Work

### Milestone 1 — identity and discovery

Add a package-private `collectDisplayPreparation(context)` hook to `Control`, recursive traversal with clip intersection to `Container`, and an `ImageControl` hook that contributes current display images and `Graphics.getContentScale()`. `ScrollContainer` starts from `bag` and clips to its scroll client, owns a monotonically increasing batch generation, and exposes only `prepareForDisplay(Runnable)`.

The request snapshot captures the target Image identity, original `EncodedImageSource` identity, exact immutable pipeline reference, decode policy/requirement, raw destination scale bits, validated physical dimensions, source decoded generation, typed effective `ImageRuntimePolicy` identity, DRAW_READY requirement, and originating batch generation. Path strings are never identity. COPY_READY remains a separate internal enum value for future use.

Batch generations suppress callbacks from superseded batches only. A stale batch does not invalidate a still-valid request. Discovery is synchronous on the UI thread and only includes visible, positive-size ImageControls intersecting the current scroll clip. Unsupported roots/formats are terminal NOT_PREFETCHABLE results. No automatic scroll/paint trigger is added.

### Milestone 2 — detached JPEG preparation and UI adoption

Clone the captured immutable encoded bytes/native bag and pipeline nodes on the worker, keeping decode state and pipeline caches separate from the live source. Reuse P5's `resolveForDrawing`/decode logic on that detached copy. A native encoded-bag copy must own its bytes; do not share a raw bag pointer without ownership. Worker output contains the detached source decoded backing/metadata, the exact-scale materialized variant, or a typed deterministic/transient failure.

Use one process-global FIFO, a bounded pending/ready identity registry, and a scheduler boundary. P8 starts a per-request worker only when it becomes queue head; the scheduler keeps it active through UI adoption and starts the next worker only after terminal adoption. No polling, Semaphore, permanent process worker, thread pool, or parallel decoder is introduced. Keep queue lock scope away from decode, UI code, resource release, and callbacks.

Adoption is posted through `MainWindow.runOnMainThread`. On the UI thread, revalidate target Image/pipeline/source identity, exact scale and physical request, source generation compatibility, and current typed policy. Batch generation is checked only before callback delivery. If a concurrent synchronous draw installed a compatible reusable source backing, keep the better live backing and discard the detached duplicate; otherwise adopt the worker backing and exact-scale pipeline variant without pixel copying. Cache deterministic decode failures on the live source only during adoption. Transient failures remain retryable on a later explicit call.

Each batch completion is accounted once per discovered candidate, including immediate ready, deduplicated, unsupported, and failure outcomes. Only the latest batch callback runs, exactly once, on the UI thread. Mark completion and release locks before callback invocation; use `finally` so callback exceptions cannot stall the FIFO.

### Milestone 3 — diagnostics, tests, and deployed smokes

Add `RuntimeDiagnosticSnapshot.Domain.PREFETCH` only if absent. Record aggregate discovered/enqueued/deduplicated/ready/stale/not-prefetchable/deterministic/transient counts and queue-depth/active-count gauges. Keep names and IDs internal, do not add traces or high-cardinality data, and make disabled/off builds return before metric work.

Add focused SDK coverage for visibility/clipping/nesting, source and pipeline identity, exact scale bits, dedupe, batch supersession, empty batches, worker/adoption ownership, synchronous-decode races, failure retry/cache, FIFO, and callback reentrancy. Add durable macOS deployed smokes for explicit success, path replacement/deletion after P5 capture, stale batch, stale request, transient retry, and deterministic corruption. Add a compact workload only if existing fixtures can exercise post-prepare draw reuse. Do not add PNG async success coverage.

## Decision Log

- Decision: keep source identity as the immutable `EncodedImageSource` object and pipeline identity as the captured immutable pipeline root/reference; never use a path or serialized chain description.
  Rationale: paths may disappear after P5 capture, while node references encode exact operation order and parameters.
  Date: 2026-09-30.
- Decision: detach both source decoded state and pipeline materialization caches before worker resolution; deep-copy encoded bytes/native bag, then adopt the detached backing/variant on the UI thread.
  Rationale: `resolveForDrawing` mutates both source reuse state and pipeline caches, so invoking it on the live Image from a worker would race ordinary drawing.
  Date: 2026-09-30.
- Decision: use a single FIFO whose active request is held until UI adoption completes; P8 starts one worker for the active head, and P9 can replace only the wake mechanism.
  Rationale: this proves one-active-through-adoption without introducing a permanent worker or P9 Semaphore.
  Date: 2026-09-30.
- Decision: prepare JPEG only and request DRAW_READY from ImageControl.
  Rationale: P8 is a display operation; PNG prefetch and independently copyable results belong to later stages.
  Date: 2026-09-30.
- Decision: use the immutable typed `ImageRuntimePolicy` snapshot by object identity as effective preparation configuration identity; keep automatic preparation false.
  Rationale: policies are immutable snapshots, and changing effective policy makes old requests non-equivalent.
  Date: 2026-09-30.

## Validation and Acceptance

Use Level 1 during implementation and Level 2 at functional commit checkpoints. Run focused SDK tests only at the end of Milestones 1 and 2, per the task contract. Do not run native builds before Milestone 2 completes.

At completion, run focused SDK tests for P8 plus P5/P1/F regressions, `artifactContentTest`, `dist -x test`, and focused diagnostics-on validation with `-PruntimeDiagnostics=true`. Build only macOS ARM64 Release `tcvm` and `Launcher`, then run P8 and P5 deployed smokes. Android, Windows, Linux, WinCE, and iOS builds are out of scope. Preserve full build output in logs and report compact summaries.

Acceptance includes `git diff --check origin/feat/image-lazy-jpeg...HEAD`, P8-only ordered commits, public API boundary, no auto-prefetch/Semaphore/pool/PNG path/path identity/masks/state file, and no unjustified large new files. Run the focused copyright-header validator on changed first-party files; for native copied/extracted code, follow provenance audit guidance.

## Risks and Open Questions

- Native encoded-bag duplication must work through the VM's registered native method table and preserve allocation/error semantics on macOS; Java fallback must copy only encoded bytes.
- `ImageControl` hardware-scale and background-image paths must use the same content scale as Graphics drawing; discovery should use the current display image and include `imgBack` only when it participates in the paint path.
- RuntimeDiagnostics feature metrics are not present in this P5 head. The implementation needs a narrow internal bridge that remains absent/no-op in diagnostics-off builds and does not expose metric IDs.
- `runOnMainThread` queues asynchronously; callback and adoption tests must pump normal UI/event processing rather than block the UI thread.
- A later P2/P5 rebase may add a backing mutation generation. Incorporate it as a separate validity field if it appears; do not substitute it for source decoded generation.

## Idempotence and Recovery

The implementation worktree is `/Users/flsobral/repos/totalcross-image-async-prefetch`, branch `feat/image-async-prefetch`. The user's original worktree and its local modifications are outside this branch and must remain untouched. If interrupted, continue in this worktree from this plan. The branch parent must remain the current P5 head; fetch and inspect before any rebase. Do not reset or clean other worktrees. Do not create `.agent/state`.

## Outcomes & Retrospective

Pending implementation.

## Revision Note

Initial plan written before feature implementation on the stacked P8 branch.
