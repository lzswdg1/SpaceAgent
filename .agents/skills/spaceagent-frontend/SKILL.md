---
name: spaceagent-frontend
description: Develop and review the SpaceAgent React/TypeScript tenant and administrator clients, including UI parity, feature architecture, public API and SSE integration, browser state, performance, accessibility, and frontend security. Use for work centered on apps/web or apps/admin-web; route backend ownership changes to the development or architecture Skill and live external acceptance to real-e2e.
---

# SpaceAgent Frontend

Work from the active React client and approved product evidence. Do not restore superseded Web
code, borrowed frontend dependencies, client-only API aliases, or presentation-only business data.

## Establish the frontend contract

1. Read `AGENTS.md`, `.agent/CURRENT.md`, and `docs/architecture/PRODUCT-ROADMAP.md`.
2. Inspect the affected package (`apps/web` or `apps/admin-web`), its App/shell, Page, API client,
   types, CSS and tests before editing.
3. Read `docs/frontend/FRONTEND-IMPLEMENTATION-PLAN.md` for the accepted product baseline and
   `docs/frontend/FRONTEND-INTEGRATION-AUDIT-2026-08-25.md` before adding or relabeling a control.
4. Read [references/frontend-architecture.md](references/frontend-architecture.md) for component,
   state, API, streaming, styling and ownership conventions.
5. For performance, security, accessibility or release review, also read
   [references/quality-gates.md](references/quality-gates.md).

## Product boundary

- React owns rendering, transient interaction state and explicit local preferences only. Java and
  PostgreSQL own authorization, Organization context, Agent/Conversation/Project state, Runtime,
  Tool effects, secrets, ledgers and checkpoints.
- Admin Web uses only `/admin/v1/**`, a distinct memory-only access token and the Admin HttpOnly
  refresh cookie. It must never reuse tenant tokens, call `/internal/**` or infer platform authority.
- Use only current public same-origin `/api/v1` contracts. Keep contract DTOs in the owning feature,
  transport behavior in `src/lib`/AuthSessionManager, and business validation on the backend.
- Preserve the native History API routing, Application Shell, lazy authenticated route chunks,
  feature-slice layout and dependency-light React hooks approach unless a demonstrated limitation
  justifies a scoped change.
- Treat `apps/web/prototypes` as design evidence, not executable product code. Preserve the accepted
  navigation order, black/warm-white theme system, light/dark parity, Chinese/Japanese/English
  coverage and established information density unless the user authorizes a redesign.
- Never make a disabled, display-only or planned control appear integrated. Update the integration
  audit whenever a visible control gains, loses or changes an authoritative backend action.

## Implementation method

1. Identify the visible outcome, authoritative API/evidence, loading/empty/error/terminal states,
   responsive behavior and keyboard behavior before coding.
2. Extend the affected feature slice rather than introducing a parallel global store, router,
   request client, design system or duplicated page.
3. Keep async state race-safe. Cancel obsolete work, block duplicate mutations, preserve current
   streams across layout changes and avoid replacing newer state with late responses.
4. Render server/model/tool text as text. Do not expose raw secrets, prompts, Tool arguments/results,
   arbitrary HTML, internal URLs/paths or unbounded provider errors.
5. Add focused deterministic tests for API paths/payloads, pure state helpers, stream framing and
   demonstrated regressions. Then run the Web test/build gates and proportionate backend contract
   tests when a public API changed.
6. Verify the affected route in both themes, relevant languages and desktop/mobile layouts when
   visual or interaction behavior changed. Check focus, scroll ownership, reduced motion and browser
   diagnostics instead of relying only on screenshots.

## Routing to the other project Skills

- Use `spaceagent-development` alongside this Skill when the task also changes an existing Java
  public contract or repository handoff state.
- Use `spaceagent-architecture` before moving authority, adding browser-owned durable state,
  changing domain lifecycles, or introducing a new frontend/backend process boundary.
- Use `spaceagent-verification` for the proportional deterministic matrix and milestone evidence.
- Use `spaceagent-real-e2e` only after explicit authorization for paid Provider, Embedding, public
  MCP/GitHub OAuth or restart-recovery acceptance. Browser clicks do not imply that authorization.

## Completion

Report the user-visible result, affected public contracts, browser-owned versus backend-owned state,
tests/build/browser checks actually run, skipped or live-not-run evidence, bundle impact when
material, integration-audit changes and Git status. Do not claim backend integration from a visual
mock, or accessibility/performance/security completion from TypeScript compilation alone.
