<!--
Copyright (C) 2026 Amalgam Solucoes em TI Ltda

SPDX-License-Identifier: LGPL-2.1-only
-->

# Implement Vertical Scroll Raster Reuse

This ExecPlan follows .agent/PLANS.md and AGENTS.md. The implementation branch
starts at origin/master revision 0176c83c5 docs(image): report runtime image
configuration. The checked-in P1 policy is present and
ImageRuntimeConfigurationStartup.currentPolicy().scrollRasterReuse().enabled()
is typed and defaults to false.

## Purpose / Big Picture

When enabled by internal policy, a safe vertical ScrollContainer movement on a
software raster surface should preserve the already-painted viewport pixels,
move them by the physical scroll delta, and repaint only the newly exposed
strip. All uncertain cases keep the existing full repaint. Production policy
remains disabled by default. The work excludes horizontal reuse, GPU surfaces,
image source-subrect drawing, and P6 APIs.

## Working Set and Resume Protocol

This plan is the sole progress record; the requested task explicitly forbids
.agent/state. Read this plan's Progress and active milestone first when
resuming. The principal implementation paths are ScrollContainer.java,
Graphics.java, GraphicsPrimitives_c.h, gfx_Graphics.c, runtime diagnostics,
and focused SDK/native tests. Read the relevant source sections before editing
each milestone. The final factual handoff is
.agent/reports/scroll-raster-reuse.md.

## Progress

- [x] (2026-09-30) Fetched origin/master, created isolated branch
      feat/scroll-raster-reuse at 0176c83c5, preserved the pre-existing dirty
      worktree, and confirmed P1's typed default-off policy.
- [x] (2026-09-30) Added the internal fallback enum and pure physical-rectangle
      planner; focused M1 tests pass without a native build.
- [x] (2026-09-30) Added the overlap-safe 32-bit native row move, guarded
      ScrollContainer integration, damage-clipped repaint, and immediate full
      repaint recovery. Simulator differential tests cover both directions,
      repeated/smooth/near-viewport steps, nested controls, and fallback states.
- [x] (2026-09-30) Added rendering-domain aggregate counters and a deployed Image
      scroll smoke. The legacy macOS primitive passed direct positive/negative
      physical-row checks; enabled scroll steps matched full repaint.
- [ ] Finish final header/diff review, write and commit the report last, then
      push and open a PR against master. Do not merge before the P6 check.

## Current Architecture and Scope

ScrollContainer.internalScrollContent changes scrollbar values, updates bag
position through bagSetRect, and sets Window.needsPaint. It currently repaints
the normal window stack. ScrollContainer.getClientRect accounts for the clipped
bag and non-transparent scrollbars. The implementation must use the actual bag
and client coordinate convention, not infer a delta sign from method names.

Graphics owns the shared physical mainWindowPixels int array and exposes its
physical width, height, pitch, and content scale. Native screen presentation
tracks damage as a union rectangle in Context and converts the 32-bit raster
buffer to the target screen format. Graphics.copyRect is a general drawing
operation with current draw/clip semantics; it is not yet proven to provide a
bounded byte-exact raster move. RuntimeEnvironment exposes RASTER/GPU, but that
fact alone does not prove the legacy renderer uses the shared array. Native
capability must also be checked. Pure planning math accepts integral scales.
Deployed native ScrollContainer reuse is currently limited to scale 1: the
macOS legacy raster renderer does not map high-density logical painting to the
whole physical framebuffer consistently, so scale > 1 selects
UNSUPPORTED_TRANSFORM. Simulator scale math remains covered independently.

P1 already exposes ImageRuntimePolicy.ScrollRasterReusePolicy.enabled and its
default is false. Consume this field only. RuntimeDiagnosticSnapshot currently
has only the RUNTIME domain; add RENDERING only if it is still absent when
diagnostic work is reached. Diagnostics are aggregate counters and must not
affect eligibility.

## Plan of Work

### Milestone 1 — Eligibility and physical rectangle math

Define one internal typed fallback reason and a pure eligibility/math helper.
Cover disabled policy, backend, zero/horizontal movement, too-large delta,
invalid viewport, unsupported transform, pixel format, framebuffer state,
pending damage, move unavailability/failure, and full-repaint-required cases.
Define checked logical-to-physical edge conversion, clipping, overflow checks,
source/destination rectangles, and overlap direction. Restrict support to an
explicitly proven 32-bit raster format if no broader format can be proven.
Prove both positive and negative delta sign conventions with tests. Pending
damage intersecting the source or destination must union safely or select the
conservative full repaint. Complete these tests before any native build.

Acceptance: deterministic reason selection, no arithmetic overflow, no
out-of-bounds rectangle, and policy-disabled behavior matching master.

### Milestone 2 — Raster move and rendering integration

Add a private native/internal same-framebuffer move only if no existing
primitive satisfies byte-exact, bounds-checked, stride-aware, overlap-safe
semantics. Move rows in the safe direction without area-sized allocation and
return failure. Keep the physical raster operation outside the public SDK API.
At the successful vertical position change, compute physical rectangles once,
move preserved pixels, and repaint the exposed strip while retaining scrollbar,
overlay, nested-control, and pending-damage correctness. If move fails, keep the
logical scroll position, mark the full viewport for repaint immediately, and
record recovery. A failed partial move must never be displayed as final output.

Acceptance: for both scroll directions, distinctive framebuffer patterns
remain pixel-identical in the preserved area; the exposed strip is repainted;
and final output equals a forced-full-repaint reference. Include near-viewport
deltas, nested controls, scrollbars/temporary handlers where applicable,
unrelated damage, resize/backend invalidation, and forced move failure.

### Milestone 3 — Diagnostics and workload smoke

After eligibility and move behavior are stable, add aggregate attempt, success,
fallback, and native failure/recovery counters through the existing internal
diagnostic path. Add Domain.RENDERING only if absent. Keep metric identifiers
and names internal and add no diagnostic clock reads. Exercise enabled mode via
an internal/test fixture that is absent from application-facing artifacts; do
not add a public switch or use RuntimeDiagnostics to enable reuse. Compare
repeated scroll steps against forced full repaint on a durable workload if one
is available. Performance results are informative only.

Acceptance: diagnostic collection does not change eligibility or repaint
behavior, and both policy-disabled and test-enabled correctness smokes pass.

### Milestone 4 — SDK and macOS verification, handoff

Run artifactContentTest and dist -x test, affected ScrollContainer/Flick/
Graphics/P1 tests, relevant Image scroll smokes, and a macOS ARM64 Release tcvm
and Launcher build. Run deployed correctness smokes with policy disabled and
test-enabled. Do not build Windows, Android, Linux, WinCE, or iOS. Do not claim
Windows performance from macOS results. Write the requested final report last,
commit it last, push feat/scroll-raster-reuse, and open a PR against master
without merging.

Before P7 merge, if P6 has merged, rebase on latest master, resolve conflicts
additively, confirm the P6 source-subrect path and P7 framebuffer reuse remain
orthogonal, and rerun full P7 validation plus relevant P6 regression smokes.

## Decision Log

- Decision: Work in a clean linked worktree rooted at origin/master instead of
  switching the existing dirty checkout.
  Rationale: preserve unrelated user edits and artifacts while creating the
  requested feature branch from the fetched latest master.
  Date: 2026-09-30.
- Decision: Keep reuse default-off and require positive proof of every
  eligibility fact; any uncertainty selects the existing repaint path.
  Rationale: stale or corrupted pixels are worse than a missed optimization.
  Date: 2026-09-30.
- Decision: Limit initial reuse to integral content scales and the shared
  32-bit main-window raster array.
  Rationale: fractional logical edge rounding can make a uniform framebuffer
  translation differ from repainting content at its new logical position.
  Date: 2026-09-30.
- Decision: Limit deployed native reuse to content scale 1 while keeping the
  row-copy primitive physical-coordinate based.
  Rationale: the macOS scale-2 integration smoke exposed pixels outside the
  mapped partial repaint, so that renderer configuration must fall back until
  its high-density raster path is proven equivalent. The primitive passed
  direct physical rectangle checks in both overlap directions.
  Date: 2026-09-30.

## Validation and Acceptance

Use AGENTS.md's smallest sufficient validation. Milestone 1 is Level 2 focused
math/eligibility tests and diff checks, with no native build. Milestone 2 is
Level 3 for the rendering operation family: focused native raster tests,
differential framebuffer fixtures, and affected SDK tests. Finalization runs
the specific artifact, distribution, macOS ARM64 Release, and deployed smoke
checks named in the source specification. Save long native/build output to
task-specific logs and summarize results. Skip Windows builds and full unrelated
platform matrices as explicitly directed.

Acceptance requires vertical raster reuse only, default OFF, conservative full
repaint on every uncertain or failed path, unchanged logical scroll/flick
semantics, exact output versus forced full repaint, no public setting, no P6
image subrect or GPU implementation, no .agent/state, and no merge before the
P6 integration/rebase check. Finish with git diff --check and an ordered commit
review against origin/master.

## Risks and Open Questions

- The implementation derives the clipped viewport from bag0's refreshed
  graphics clip and translation, then clips against the physical framebuffer.
- Runtime backend code alone does not prove legacy native move support. The
  native primitive returns unavailable for Skia/GLES builds, and native scale
  > 1 is rejected before the move.
- Window.needsPaint is checked before planning and native Context dirty bounds
  are checked under the screen lock; either conflict selects a full repaint.
- The smoke source set enables the package-private test policy and is packaged
  only by verification tasks; no application-facing switch is added.
- P6 is not merged in the fetched base. Rebase and interaction validation are
  a required pre-merge follow-up if that state changes.

## Idempotence and Recovery

All work stays on feat/scroll-raster-reuse in the isolated linked worktree.
Never alter or clean the original worktree's benchmark logs, artifacts, scripts,
workspace file, generated outputs, or downloaded dependencies. Retry fetches
and focused validation without deleting local caches. If a copy partially
fails, immediately request full viewport repaint while retaining the computed
scrollbar and bag position. Commits are additive and use the order in the source
specification; do not amend or rewrite user-authored history.

## Outcomes & Retrospective

Milestone 1 is complete: fallback selection is deterministic; logical edges
convert with Graphics' nearest-edge rule; viewport clipping, scroll sign,
overlap order, scaling, and arithmetic rejection are covered by the focused
SDK test. At completion, summarize delivered behavior, fallback limits,
supported backends/formats, validation results, performance evidence scope,
and the pending P6 integration requirement. The detailed final factual handoff
is in .agent/reports/scroll-raster-reuse.md.

## Revision Note

- Initial plan created before implementation on 2026-09-30 from the fetched
  origin/master revision; M1 checkpoint recorded after focused tests passed.
