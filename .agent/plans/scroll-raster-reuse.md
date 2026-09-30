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
- [ ] Commit this plan before implementation. Then complete Milestone 1
      eligibility and rectangle math tests without a native build.
- [ ] Complete Milestone 2 raster move and ScrollContainer integration with
      correctness comparison against forced full repaint.
- [ ] Complete focused SDK and macOS validation, write and commit the report,
      then push and open a PR against master. Do not merge before the P6 check.

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
bounded byte-exact raster move. RuntimeEnvironment has finalized backend facts;
the native backend code distinguishes GLES from non-GLES, so eligibility must
not treat that fact alone as proof of a software raster backend.

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

- The exact physical coordinate of ScrollContainer's clipped bag may include
  ancestors, borders, content scale, and transparent/temporary scrollbars;
  resolve from current paint and clipping behavior before implementation.
- Runtime backend code 1 groups every non-GLES configuration. Find a reliable
  raster capability proof before allowing reuse; otherwise fall back.
- Window.needsPaint is global and native dirty bounds are accumulated in
  Context. Establish when pre-existing damage can be observed safely; any
  ambiguity must choose full viewport repaint.
- Confirm how the current test and smoke source sets can enable the internal
  policy without shipping a runtime switch in application artifacts.
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

Not started. At completion, summarize delivered behavior, fallback limits,
supported backends/formats, validation results, performance evidence scope,
and the pending P6 integration requirement. The detailed final factual handoff
is in .agent/reports/scroll-raster-reuse.md.

## Revision Note

- Initial plan created before implementation on 2026-09-30 from the fetched
  origin/master revision. It records the known architecture boundaries and
  validation order without treating historical branches as implementation.
