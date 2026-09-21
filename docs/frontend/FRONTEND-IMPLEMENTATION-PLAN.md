# SpaceAgent Web Implementation Plan

> Started: 2026-08-24
> Owner: Web interaction layer
> Backend authority: `apps/platform-server`
> Design baseline: `apps/web/prototypes/`

## Objective

Build the first-party SpaceAgent Web client in React 19 and TypeScript from the approved
HTML prototypes, while integrating only the authoritative `/api/v1` platform-server
  contracts. The browser owns presentation, transient form state, language/theme preferences
and memory-only access-token transport; Java/PostgreSQL continue to own all business, authorization,
Runtime, Provider-secret and side-effect state.

## Non-negotiable boundaries

- No copied frontend code or removed compatibility contracts may return.
- No fake business data in authenticated production routes.
- No browser-owned Project, Task, Conversation, Run, Approval or Trace truth.
- Never call `/internal/**` or `/api/v1/internal/**` from the Web client.
- Never log access tokens, refresh tokens, Provider secrets, MCP auth references or prompts.
- Same-origin `/api` is the deployment contract; Vite proxies `/api/` to port `9000`.
- Every milestone must build, test, update this plan and land as a separate Git commit.

## Frontend architecture

```text
apps/web/src/
├── app/                 app composition, native route table, guards, providers
├── components/          first-party reusable UI primitives and application shell
├── features/
│   ├── auth/            login/register/logout/session recovery
│   ├── organizations/   list/switch/current organization
│   ├── overview/        monitoring overview
│   ├── chat/            Chat conversations and SSE
│   ├── projects/        Projects, Tasks, Workspaces and Project conversations
│   ├── agents/          Agent definitions, versions and knowledge bindings
│   ├── knowledge/       document lifecycle and retrieval configuration
│   ├── models/          Providers, models, connection tests and ModelPools
│   ├── mcp/             marketplace/installations/connections
│   └── tracing/         user-scoped conversation/run evidence
├── lib/                 API envelope, auth transport, error mapping, SSE helpers
├── styles/              tokens, themes, shell and feature styles
└── test/                deterministic fixtures and render helpers
```

The production client will use a small native History API router unless a later feature
proves a concrete need for a routing dependency. Domain DTOs are defined from the current
Java HTTP records and kept feature-local; there is no replacement shared-contract package.

## Authentication/session contract

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/users/me`
- `GET /api/v1/organizations`
- `POST /api/v1/organizations/{organizationId}/switch`

Responses use `{ success, data, message }`. The access token is sent only as a Bearer
header and retained only in memory. Current browser auth uses `/api/v1/web/auth/**`, an HttpOnly
refresh cookie and single-flight rotation. Earlier progress-log storage/version descriptions are superseded.
Refresh is single-flight, retries one failed request once, and clears the complete session
on replay rejection or terminal authentication failure.

## Milestones

### F0 — Audit, plan and approved prototype baseline

Status: **COMPLETE**

- Confirm active Web foundation and authoritative backend topology.
- Map native Identity/Organization contracts and security boundaries.
- Preserve approved prototypes as non-production design evidence.
- Add this resumable plan and record commit/validation policy.

Validation:

- `npm run build`
- focused platform Identity/Public HTTP tests
- `scripts/check-architecture.sh`
- `git diff --check`

### F1 — Native authentication and Organization session

Status: **COMPLETE**

- Add typed API envelope/error handling and Bearer transport.
- Add token persistence, single-flight refresh and logout.
- Wire the approved sign-in/register UI to native backend requests.
- Recover `/users/me` and Organization list on reload.
- Add protected `/app` route and explicit loading/error/expired-session states.
- Add deterministic auth/session tests.

Acceptance:

- Presentation-only timeouts are removed from auth forms.
- Successful login/register enters a protected application route.
- Refresh recovery and replay rejection are tested.
- No Better Auth, compatibility alias, internal endpoint or fake session remains.

Planned commit: `feat(web): integrate native authentication`

### F2 — Application shell, theme, language and Organization switching

Status: **COMPLETE**

- Convert the approved sidebar/header/theme prototypes into reusable React components.
- Preserve Chinese/Japanese/English and dark/light preferences.
- Render the authenticated user and active Organization from backend DTOs.
- Switch Organization through the native token contract with confirmation and session
  replacement.

Planned commit: `feat(web): add authenticated application shell`

### F3 — Overview observability

Status: **COMPLETE**

- Integrate `/api/v1/monitoring/overview`, `/usage` and `/realtime`.
- Render settled tokens/cost only from backend evidence; never estimate missing price data.
- Preserve the approved slow orbital reveal and solid card surfaces.

Planned commit: `feat(web): integrate overview observability`

### F4 — Chat conversations and streaming

Status: **COMPLETE**

- Integrate Conversation CRUD/messages and `/api/v1/chat/messages/stream`.
- Implement cancellable SSE parsing, terminal/error states and session recovery.
- Keep Conversation a UI container; Runtime/AgentRun remains authoritative in Java.

Planned commit: `feat(web): integrate chat conversations`

### F5 — Project workspace

Status: **COMPLETE**

- Integrate Projects, Tasks, Workspaces and Project conversations.
- Render project folder -> conversation -> Agent hierarchy from authoritative DTOs.
- Never send arbitrary server-local paths from the browser.
- Keep TaskPlan authoring and Source/Workspace provisioning in the later resource/control
  plane slice; this page only reads the Project-owned context already exposed by the
  backend.

Planned commit: `feat(web): integrate project workspace`

### F6 — Resource and control-plane modules

Status: **COMPLETE**

- Agent definitions/versions/knowledge bindings. **COMPLETE**
- Knowledge documents/import/retrieval. **COMPLETE**
- Providers/models/connection tests/ModelPools. **COMPLETE**
- MCP marketplace/installations/connections. **COMPLETE**
- User-scoped tracing. **COMPLETE**
- Organization settings. **COMPLETE**
- Credentials remains an explicit later-iteration placeholder unless a public vault API is
  separately authorized.

This milestone may be split into several independently shippable commits.

### F7 — Release verification

Status: **COMPLETE**

- Production build, deterministic frontend tests and architecture gate.
- Same-origin Nginx and Vite proxy smoke.
- Authorized API regression with isolated local backend fixtures.
- Responsive, keyboard, focus, reduced-motion and empty/error state review.

## Progress log

- 2026-08-24: F0 complete. Clean-room foundation build, platform HTTP focus tests and
  architecture validation passed. M32-PR2B authentication work started.
- 2026-08-24: F1 complete. Native login/register, bounded token persistence,
  single-flight refresh, logout/revocation, user/Organization recovery and protected
  `/app` routing are implemented. Isolated H2 live API and Vite proxy acceptance passed.
- 2026-08-24: F2 complete. The authenticated React shell now uses the approved sidebar,
  responsive navigation, Chinese/Japanese/English, dark/light themes and confirmed native
  Organization switching. Isolated live create/list/switch acceptance passed.
- 2026-08-24: F3 complete. Monitoring overview, agent usage and realtime endpoints now
  render the approved dashboard with real empty/error states and no estimated cost.
- 2026-08-24: F4 complete. Chat now lists/creates authoritative Conversations, restores
  messages and consumes cancellable Runtime SSE without synthetic browser output.
- 2026-08-24: F5 complete. Project mode now lists/creates authoritative Projects, groups
  project-scoped Conversations under their owning Project, reads Tasks/Workspaces and
  reuses the cancellable Runtime SSE path without accepting arbitrary filesystem paths.
- 2026-08-24: F6 Agent slice complete. Agent definitions now load/create/update through
  public APIs with real ModelPool choices, Knowledge bindings, limits, permission/network
  boundaries, Skill/Tool identifiers and version history. The sandbox is shown truthfully
  as a platform-managed isolated worker because no browser-writable sandbox mode exists.
- 2026-08-24: F6 Knowledge slice complete. Local text/Markdown/JSON/CSV and pasted text
  use the real document processing/embedding pipeline; online URLs create explicit external
  references and remain unindexed until a connector supplies content. Chunk evidence,
  retrieval testing and confirmed deletion are implemented.
- 2026-08-24: F6 Model slice complete. Provider creation stores allowlisted Base URLs and
  API keys only through the backend, model catalogs are editable, connection tests are
  explicit user actions, and routing pools support membership plus activation lifecycle.
- 2026-08-24: F6 MCP slice complete. The live Catalog, user/Organization installations,
  HTTPS Streamable HTTP connections, encrypted auth handoff and official GitHub host OAuth
  begin/callback are integrated. Custom MCP maps to the backend's seeded custom entry;
  the browser cannot invent Catalog records.
- 2026-08-24: F6 tracing slice complete. AgentRun traces are grouped under their owning
  Conversation, then split into Chat or Project views using authoritative trace metadata.
  Run/Span evidence is fetched on selection; no pause/export controls or synthetic traces
  are present.
- 2026-08-24: F6 Organization slice complete. Current Organization/member evidence,
  confirmed context switching and create-then-switch use authoritative Identity contracts.
  Agent sharing remains disabled because the backend exposes owner-scoped definitions only;
  credentials remains the requested later-iteration placeholder.
- 2026-08-24: F7 complete. All nine authenticated routes passed desktop and 390x844
  browser checks, including no document-level horizontal overflow, a scrollable nine-item
  mobile navigation, visible keyboard focus, theme/language persistence and zero browser
  warnings/errors. Same-origin Vite API/fallback, focused backend HTTP tests, Web tests,
  production build and architecture gates pass.
- 2026-08-24: post-release visual parity fix. The public entry now uses the complete
  approved particle physics from `spaceagent_hero_base.html`—non-uniform streams, staged
  collapse, depth/temperature, photon ring, trails, lensing and text avoidance. The same
  system renders in a separately clipped Canvas on the authentication left panel.
- 2026-08-24: Provider-test and brand follow-up. Connection testing now reports in-modal
  progress, terminal success/failure, latency, discovered models, test time, error code and
  credential guidance; transport errors can no longer hide behind the modal overlay. All
  SpaceAgent wordmarks share a 400-weight classical serif italic lockup with the approved
  two-line Dylan Thomas motto.
- 2026-08-25: lifecycle parity audit complete. Provider edit/delete, ProviderModel removal,
  ModelPool member removal, Agent archive, MCP connection edit/revoke and installation
  disable, Project edit/archive, plus Chat/Project Conversation delete are exposed with
  explicit confirmation. Knowledge already had confirmed deletion. No browser-only rename
  or hard-delete action is invented where the backend exposes no such contract.
- 2026-08-25: Model onboarding/readability correction. Provider detail now explains the
  exact order—repair credentials, pass connection test, then add the provider's exact model
  ID—and successful discovery can prefill the model form. Model-management typography was
  raised to readable 11–15px controls/status text and a 38px dialog title.
- 2026-08-25: ProviderModel import false-failure fix. The async form handler now captures
  its form before awaiting the backend, so successful creation cannot dereference a cleared
  React `currentTarget`. Model and Pool mutation errors stay inside the modal, while model
  creation shows an explicit success toast.
- 2026-08-25: per-model diagnostics and Agent model discovery. Each configured model can
  execute a backend-owned bounded `hi` inference probe and display response preview,
  latency, input/output tokens and safe failure code. Agent direct binding loads only models
  from ACTIVE Providers and exposes a regex-safe, input-attached autocomplete that fills
  Provider ID and Model ID together.
- 2026-08-25: Overview/Chat prototype parity follow-up complete. The real-data Agent Token
  visualization now replays the approved slow segmented orbital reveal and one-lap probe on
  entry/range change while its center value counts up. Chat restores the compact auto-growing
  composer, persisted navigation/inspector collapse controls and bounded independent scrolling
  for all three working columns without changing navigation order or unrelated routes.
- 2026-08-25: Conversation Agent routing/readability follow-up complete. The Chat inspector
  selects an ACTIVE Agent through the new persisted Conversation switch contract, disables the
  control during active execution and resets stale run-summary evidence after a successful
  switch. Authenticated microcopy now follows a 10/12/13/15px hierarchy with adjusted line
  heights across navigation, Tracing, Chat, inspectors, cards and dialogs; layout geometry and
  navigation order remain unchanged.
- 2026-08-25: post-switch Provider request correction complete. Chat now derives tool exposure
  from the selected AgentVersion instead of globally enabling the built-in echo tool. Zero-tool
  Agents omit `tools`; supported enabled IDs map to known definitions. Provider 4xx messages are
  bounded/redacted by Java before surfacing through the existing Chat error state.
- 2026-08-25: Chat/Project composer parity correction complete. The global offset textarea
  focus outline is suppressed inside workspace composers while the component border remains
  the focus signal. Project now matches Chat's bounded flex layout, 38–76px growth, controls
  and keyboard behavior instead of occupying the message-log stretch row.
- 2026-08-25: Project input/brand correction and full integration audit complete. Project
  text entry is available before Project selection while send remains authority-gated; the
  authenticated brand is wordmark-only. Active controls and backend contracts are classified
  in `FRONTEND-INTEGRATION-AUDIT-2026-08-25.md`, including no-op, partial and missing surfaces.
- 2026-08-25: audit integration slice 1 complete. Chat search is functional, Project
  Conversation Agent routing uses the persisted switch endpoint, and the unsafe no-op Forgot
  password affordance is removed pending a verified recovery contract.
- 2026-08-25: audit integration slice 2 complete. Agent Tool/Skill configuration is backed by
  a Java Runtime capability catalog and rejects unknown IDs; Project Task creation plus active
  Task binding is wired; Knowledge online-reference copy now matches backend behavior.
- 2026-08-25: audit integration slice 3 complete. Project adds parent/child Tasks, legal Task
  lifecycle actions and one-step TaskPlan authoring plus the complete review/activation state
  machine. Source/Workspace mutation remains fenced behind a future authorized-source slice.
- 2026-08-25: audit integration slice 4 complete. Project reuses ACTIVE GitHub MCP Connections,
  explicitly loads account repositories or accepts one public GitHub URL, imports path-free
  SourceRepository evidence with idempotency and provisions/archives Project-owned Workspaces for
  non-terminal Tasks. Local paths, Bridge Tokens and checkout credentials remain outside Web.
- 2026-08-25: M33-PR2 Runtime Tool catalog binding complete. The existing Agent card consumes the
  backend's full capability metadata, safely handles rolling-restart legacy fields, prevents
  unavailable additions and surfaces network/Workspace policy without changing navigation, card
  order or grid geometry.
- 2026-08-25: M34-PR2 sandbox status binding complete. The existing Agent Execution Boundary row
  renders the backend's secret-free in-process/HTTP and compatibility/OCI evidence; the browser has
  no sandbox toggle, token, URL, image, socket or path. SearXNG remains pending a real HTTPS URL.

## Validation evidence

- `npm run build`: 31 modules; 224.09 kB JS / 71.89 kB gzip; 18.79 kB CSS / 4.83 kB gzip.
- `./scripts/check-architecture.sh`: pass.
- Focused `PlatformPublicHttpTest`, `PlatformIdentityHttpTest`, `PlatformAgentHttpTest`,
  `PlatformKnowledgeHttpTest`, and `PlatformAutomationHttpTest`: pass.
- Web auth tests: 4 passed, covering login context, local/session persistence,
  concurrent-401 single-flight refresh and terminal refresh rejection cleanup.
- Isolated live acceptance: register, `/users/me`, Organization list, refresh rotation,
  logout and revoked-token 401 passed without emitting token material.
- Vite same-origin proxy and `/app` document fallback returned HTTP 200.
- Organization switch test replaced the bearer token and reloaded current context; isolated
  live acceptance created a second Organization and proved the switched tenant ID.
- Overview loader test covers all three monitoring routes. Isolated live acceptance returned
  successful Overview/Usage/Realtime envelopes with zero real data and unknown cost, which
  the UI preserves as an evidence-aware empty state.
- SSE parser test proves chunk-boundary handling. Isolated live acceptance proved Agent and
  Conversation creation, message history, Runtime events, delta and done boundaries.
- Project API test proves canonical Task/Workspace route construction. Isolated live
  acceptance created a Project, Task and project-scoped Conversation, then proved the
  returned `projectId` and `activeTaskId` match their authoritative owners.
- Project checkpoint verification: 4 test files / 8 tests passed; production build emitted 49
  modules, 263.43 kB JS / 83.69 kB gzip and 43.83 kB CSS / 8.62 kB gzip.
- Agent API tests cover public resource loading, encoded version routes and complete
  definition writes. Isolated live acceptance proved direct Provider binding, max-turn and
  token updates, network permission, two Skill IDs, one Knowledge binding and two version
  snapshots. Browser QA passed dark/light and Chinese/Japanese switching with no console
  errors. Latest verification: 5 test files / 10 tests passed; 52 production modules.
- Knowledge API test covers reference creation, processing, encoded Chunk routes and
  retrieval. Isolated live acceptance proved READY processing, one durable Chunk, one
  retrieval match and UPLOADED-only external reference semantics. Latest verification:
  6 test files / 11 tests passed; 55 production modules.
- Model API tests cover Provider/Pool loading and encoded Model/test/member/status routes.
  Isolated live acceptance created one Provider, two models and one pool; the backend
  correctly rejected membership before a real connection test. Latest verification:
  7 test files / 13 tests passed; 58 production modules.
- MCP API tests cover the three-part registry loader, encoded installation/connection
  mutations and GitHub OAuth begin. Isolated live acceptance returned two Catalog entries,
  an ACTIVE custom connection and PENDING_AUTH official GitHub connection. Latest
  verification: 8 test files / 15 tests passed; 61 production modules.
- Tracing tests cover Chat/Project Conversation grouping, token aggregation and exclusively
  user-scoped list/stats/detail routes. Isolated H2 live acceptance proved successful empty
  List/Stats envelopes; non-empty trace evidence remains PostgreSQL-view acceptance.
  Latest verification: 9 test files / 17 tests passed; 64 production modules.
- Organization tests cover encoded detail/member routes and creation without browser-owned
  policy fields. Isolated live acceptance proved current membership, Organization creation
  and switched-token tenant identity. Latest verification: 10 test files / 19 tests passed;
  67 production modules.
- Final Web verification: 10 test files / 19 tests passed. Production output contains 67
  modules, 339.55 kB JS / 101.89 kB gzip and 83.50 kB CSS / 13.48 kB gzip.
- Focused backend release regression passed `PlatformPublicHttpTest`, Identity,
  Organization, Agent, Knowledge, ModelPool, MCP Marketplace, Observability and Tracing
  HTTP suites.
- Browser release fixture used real isolated API records for Agent, Chat/Project
  Conversations, Project/Task, Knowledge, Provider/ModelPool, MCP and Organizations.
  Desktop and mobile route checks, keyboard focus, reduced-motion CSS, responsive
  navigation, theme metadata and console diagnostics passed.
- Particle parity browser QA passed at the public hero's 81% scroll stage and on the
  authentication left panel; both Canvas surfaces rendered without browser warnings or
  errors, and reduced-motion still collapses animation to a deterministic frame.
- Provider browser QA used an isolated invalid credential and visibly returned
  `Connection failed / PROVIDER_AUTH_FAILED`, 314ms evidence, timestamp and corrective
  guidance from the real backend test endpoint. Brand computed styles confirmed italic
  presentation and identical `400` weights for both words; browser diagnostics stayed clean.
- Lifecycle acceptance used a dedicated tenant and proved Provider update/disable/delete,
  ProviderModel delete, Agent archive, Knowledge delete, Project update/archive,
  Conversation delete and MCP revoke/disable terminal states through live APIs. Eleven Web
  test files / 21 tests, focused public HTTP suites, production build, architecture and
  browser action/confirmation checks pass with no console warnings or errors.
- Model onboarding browser QA confirmed the three-step guide, `qwen-plus` placeholder,
  38px title, 15px status, 13px input and 11px labels/buttons without console errors.
- Browser regression created `reset-fix-model`, displayed it immediately and showed the
  success toast with no null-reset text or console warnings/errors. Eleven Web test files /
  21 tests, production build and diff checks remain green.
- ProviderModel application/HTTP tests prove the 128-token bounded probe and safe preview;
  Agent autocomplete tests prove `d` selects `deepseek-v4-pro-0813`, Provider-name matching
  and regex metacharacter escaping. Twelve Web test files / 24 tests, focused Java tests,
  production build and architecture pass.
- Overview/Chat parity verification: twelve Web test files / 24 tests and the production
  TypeScript/Vite build pass. A fresh local Vite page loads with no console warnings or errors;
  authenticated visual interaction remains available at the running local app for operator QA.
- Conversation Agent/readability verification: eight focused Java Conversation/HTTP tests,
  twelve Web test files / 25 tests, production build, architecture and diff checks pass. The
  local persistent PostgreSQL backend restarted at schema V1035 and reports readiness UP.
- Provider-compatible Chat verification: sixteen focused Runtime/Inference tests prove
  authorized tool mapping, existing echo events and secret-safe 4xx evidence. Web 25 tests,
  production build, architecture and diff checks pass; no paid Provider call was executed.
- Composer browser QA measured Chat and Project forms at the same 860×88 geometry with 38px
  initial textareas, `outline-style:none` after focus and zero console warnings/errors. Web
  25 tests and the production build pass.
- The integration audit cross-checked all nine active product routes, feature API clients and
  matching Java HTTP surfaces. Web 25 tests and production build pass after the Project input
  and brand correction.
- Slice 1 verification: conversation filtering has a deterministic title/Agent-name test;
  thirteen Web test files / 26 tests and the production build pass. Existing Conversation
  Agent API coverage continues to prove ownership and persistence.
- Slice 2 verification: nine focused Java Tooling/Agent/Task/Conversation tests, thirteen Web
  test files / 27 tests, production build, architecture and diff checks pass. PostgreSQL V1035
  restarted healthy; no external Provider/MCP/Knowledge fetch was invoked.
- Slice 3 verification: three focused Java Task/TaskPlan/Conversation tests, thirteen Web test
  files / 28 tests, production build, architecture and diff checks pass. No external Git/MCP
  operation or backend schema change was performed.
- Slice 4 verification: ten focused Java SourceRepository/GitHub MCP/Workspace tests, thirteen Web
  test files / 30 tests and the production build pass. Architecture/diff gates pass; automated
  validation does not call remote GitHub/MCP and no backend schema change is introduced.
- M33-PR2 verification: twelve focused Java Tool Catalog/Runtime/Agent tests, fourteen Web test
  files / 33 tests and production build pass. Architecture/diff gates pass and automated validation
  performs no external Tool effect.
- M34-PR2 verification: nine focused Java Catalog/HTTP tests, fourteen Web test files / 33 tests,
  production build, architecture and diff gates pass. No sandbox command/Search call was performed.
- Agent Save follow-up: Tool network/unavailable conflicts now produce an immediate top-level
  explanation on submit rather than a visually indistinguishable disabled Save control. Genuine
  disabled states are styled; fourteen Web test files / 34 tests and production build pass.
- M35-PR1 verification: actual Provider SSE content/reasoning and incremental Tool Call parsing,
  asynchronous Chat SSE and second-round Tool-result synthesis pass nine focused Java tests. Chat
  and Project share an animated/collapsible reasoning view; fourteen Web test files / 35 tests and
  production build pass without an automated paid Provider call.
- Streaming layout follow-up: left navigation collapse no longer changes the translation callback or
  reloads history during an active stream. A bottom-aware ResizeObserver keeps the latest answer
  visible through either sidebar width transition while respecting manual scroll-up. Fifteen Web
  test files / 37 tests, production build and architecture pass.
- External DP-V4-Pro evidence: the workspace `/models` directory probe passed and the new
  endpoint was reached, but the original 16-token diagnostic produced no acceptable final
  response. The bound was corrected to 128 tokens after the single allowed retry; no third
  paid call was made. Both isolated stacks and temporary Secrets were removed.
- 2026-09-05 MCP M50 frontend alignment: the tenant Registry now exposes Publisher/trust/current
  Version evidence, loads approved ServerVersions on demand, pins Installation to the selected
  Version, distinguishes specialized GitHub OAuth from generic pre-registered OAuth without
  persisting OAuth state, and exposes explicit Connection qualification plus bounded capability/
  health evidence. New/reconfigured Connections are not presented as usable before `ACTIVE`.
- The independent Admin Web now exposes official Registry Sync Jobs and review Candidates with
  bounded filters/pages, safe detail evidence and recent-MFA/reason/idempotent sync/approve/reject
  commands. It does not render raw manifest/header JSON and never auto-publishes synchronized data.
- Deterministic frontend tests do not invoke Registry, OAuth or MCP endpoints. Real OAuth,
  qualification and official Registry synchronization remain LIVE NOT RUN.
- Validation: tenant Web 15 files / 39 tests and Admin Web 3 files / 21 tests pass; both production
  builds pass. Focused Marketplace/GitHub/generic-OAuth HTTP contracts pass 3/3 and the Admin exact-
  scope Registry client passes 4/4, with no public Registry or remote MCP request.

## Remaining work

### 2026-09-09 AgentVersion governance alignment

- Agent definition saves already create immutable Draft snapshots. The Agent workspace now exposes
  their submit/reconcile state, a bounded Organization-wide review inbox and exact-revision review
  and publication decisions.
- Publish, deprecate and rollback require explicit confirmation and only affect future Runs; the UI
  does not rewrite existing pinned Run evidence.
- The new public inbox is server-paginated, membership-authorized and may filter by Agent/state.
  Decision and schedule ownership is derived from persisted review scope rather than browser input.
- Follow-up alignment adds revision-fenced separation/TTL/automatic-scheduling policy editing,
  lazily loaded review comments and decision evidence, plus a review-scoped persistent schedule
  list with terminal time and safe error code. Schedule creation reconciles by its database-unique
  action/time identity and invalid lifecycle combinations fail before persistence.
- `requireReview` remains visibly fixed because the publication coordinator requires an exact
  approved review even if a historical policy row says otherwise. Schedule cancellation remains
  unavailable because no public cancellation contract exists.
- No Provider, MCP, Sandbox or external service is invoked by deterministic tests.
- Validation: focused Web API `6/6`, Agent review Application/HTTP `9/9` and PostgreSQL `4/4`;
  full Web `50/50`, full Maven `703/703` (24 shared + 668 platform + 11 admin), production Web
  build, architecture and diff gates pass. The lazy Agent chunk grows by about 2 KB gzip without a
  new dependency.
- Governance follow-up validation: focused Web API `7/7`, review Application/HTTP `11/11` and
  schedule PostgreSQL `4/4`; full Web `51/51` and clean full Maven `705/705` (24 shared + 670
  platform + 11 admin), production build, architecture and diff gates pass. One earlier full run
  crossed a workstation sleep interval and expired six unrelated lease fixtures; all four affected
  classes passed `17/17` in isolation before the required clean full rerun passed.

### 2026-09-06 Project M51-M53 frontend alignment

- Project navigation now follows `Project -> ProjectDirectory -> Conversation`; creation and
  Workspace provisioning submit the exact directory and SourceRepository binding.
- A published current Conversation AgentVersion can enqueue a directory Intake. Durable proposals
  show Root Task/child/step evidence and require explicit hash-bound confirm or reasoned reject.
- The inspector reads current Plan-step Coding Jobs, Project Handoffs and captures immutable
  Recovery Packages without rendering patch, prompt, Tool payload or credential content.
- Project Chat consumes SSE `suspended` events. Exact approvals can be decided and resumed on the
  same Run; verifiable Workspace UNKNOWN effects call the bounded reconciliation contract and never
  redispatch the Tool blindly.
- M56/M57 follow-up: an APPROVED/ACTIVE TaskPlan can now be executed with the current published
  AgentVersion and a distinct published Reviewer AgentVersion. The inspector reads the durable
  ProjectPlanExecution and active Coding Jobs rather than inferring progress from the browser.
- Project Task intent editing now persists all five mutable intent fields through the canonical
  owner-authorized PATCH contract. Waiting/blocked Coding Jobs can create an idempotent,
  same-directory cross-Conversation/Agent Handoff with exact published target and Reviewer
  AgentVersions; returned Handoff, target Job and Recovery Package evidence immediately reconciles
  the inspector.
- M57-PR2-U06 alignment is complete. New Plan execution uses the authenticated durable Execution
  route with an idempotency key; the inspector loads the redacted control projection and sends
  revision-fenced pause/resume/cancel commands. Pause/cancel require a bounded reason, PAUSING and
  CANCELLING remain visible until the backend confirms a safe terminal boundary, and active states
  refresh every four seconds only while the page is visible.
- 2026-09-08 M56/M57 alignment validation: the Project Plan execute/durable Execution client paths
  pass focused API coverage, `PlatformProjectHttpTest` passes 8/8, the tenant suite passes 15 files /
  43 tests, and the 75-module production build passes. No Provider, GitHub, MCP, Sandbox or user
  credential was invoked.
- 2026-09-08 Task/Handoff alignment validation: the Project API suite passes 11/11, the tenant
  suite passes 15 files / 45 tests, the 75-module production build and architecture/diff gates pass.
  The focused Java HTTP selection is temporarily BLOCKED at test compilation by concurrent
  M57-PR2-U05R work in `ProjectCodingCoordinatorTest`; no failing source is in this frontend slice
  and no external Provider, GitHub, MCP, Sandbox or user credential was invoked.
- 2026-09-08 M57 control alignment validation: the Project API suite passes 12/12, the tenant suite
  passes 15 files / 46 tests, the 75-module production build and `PlatformProjectHttpTest` 8/8 pass.
  Pause/resume/cancel paths, payload revisions and redacted control reads are covered without a live
  Provider, GitHub, MCP, Sandbox or credential operation.
- 2026-09-09 M64 Knowledge URL alignment: online import now creates a bounded Java-owned URL Job
  with one-time or periodic refresh policy, visible lifecycle/revision evidence, current-session
  pause/resume and page-visible polling until the document reaches READY/FAILED. A public
  list-by-document contract does not exist, so Job controls are intentionally not persisted or
  fabricated across browser reload. Knowledge API tests pass 2/2, the tenant suite passes 15 files /
  47 tests, the 75-module production build and focused Java URL domain/application/HTTP tests 6/6
  pass. No deterministic test fetched a public URL or invoked a paid Embedding provider.
- 2026-09-09 Overview Session control alignment: the dashboard loads the first 25 owner-scoped
  Session summaries alongside overview/usage/realtime evidence and can confirm-stop one active Run
  or the active Runs on that loaded page. Both commands reload backend truth and preserve partial
  batch failures explicitly. Overview API tests pass 2/2, the tenant suite passes 15 files / 48
  tests, the 75-module production build and focused Observability application/HTTP tests 4/4 pass.
  No Provider, MCP, Sandbox or external service was called.
- Validation: Project/Chat focused Web 12/12, full tenant Web 15 files / 42 tests, production build,
  architecture and scoped diff gates pass; focused Project/approval/UNKNOWN HTTP tests pass 10/10.
  The broad Maven run is BLOCKED by concurrent V1049 backend work (two stale assertions and eight
  backend fixture/class errors); none originates in the frontend files, and no external call ran.

## M67 remaining public-contract parity (started 2026-09-10)

The user reopened frontend integration after M66. M67 runs as two five-Unit batches: Organization lifecycle,
Automation, Memory, inference governance, Project assignment/defaults, dependency editing, Knowledge Job recovery,
Overview navigation, honest no-op/control parity, and final audit. Each visible action must use a current public
backend contract and authoritative refresh; absent contracts remain explicitly unavailable rather than simulated.

M67-PR1-U01 is active for Organization members, invitations, role/reactivation, ownership transfer and safe leave.
External operator acceptance still requires explicit authorization and is not part of M67 deterministic work.

### M67-PR1-U01 implemented pending B01

- Organization Settings now exposes the existing public member, invitation, ownership-transfer and leave contracts.
- Manager controls are presentation-gated and always reauthorized by Java. Destructive/privilege changes require
  confirmation and reload backend truth; the one-time invitation token is component-memory only.
- Focused Organization API tests pass `4/4` and the production build passes. HTTP proof is deferred to M67-B01.

### M67-PR1-U02 implemented pending B01

- Added a lazy Automation page over public scheduled-task contracts: create/edit, pause/resume, explicit manual
  trigger, archive, Schedule truth and bounded execution evidence.
- Trigger uses one per-action idempotency key and no browser retry. Backend approval/UNKNOWN states remain visible
  evidence rather than client-owned transitions. Web API `3/3` and the 79-module production build pass.

### M67-PR1-U03 implemented pending B01

- Added a bounded public pending-candidate route and a lazy Memory page for scope-authorized recall, proposal,
  accept/reject and Task consolidation/promotion evidence.
- Backend authorization/scoring/lifecycle remains authoritative. Web API `3/3`, Java compile and the 82-module
  production build pass; HTTP execution remains in M67-B01.

### M67-PR1-U04 implemented pending B01

- Existing Model Resources now exposes Organization inference policy/usage and manager configuration plus immutable
  Provider Model price versions/effective-window creation.
- Java owns permission, overlap and cost accounting. Web API `4/4` and production build pass; HTTP proof remains B01.

### M67-PR1-U05 implemented pending B01

- The existing Project inspector now configures PlanStep defaults/overrides using published primary and distinct
  Reviewer AgentVersions. The selected primary version supplies its immutable ModelPool binding.
- React submits semantic identifiers only and displays the returned revision/hash evidence. Runtime derives and
  validates capability/configuration hashes server-side for HTTP, automatic dispatch and handoff.
- Project API `13/13`, Runtime assignment `6/6`, production build and Java compilation pass; B01 remains pending.

### M67-PR1-B01 complete

- U01-U05 pass the combined tenant Web suite (`65/65`), production build and Java/PostgreSQL owner matrix (`55/55`,
  zero skipped). The first Docker-inaccessible attempt was rejected rather than counted.
- U06 multi-step TaskPlan dependency editing is next. No live Provider/MCP/GitHub/Sandbox acceptance ran.

### M67-PR1-U06 implemented pending B02

- Project TaskPlan creation supports multiple Steps with stable keys, unique direct child Tasks, dependency selection,
  optional capability/Agent, output, acceptance criteria and approval policy. Existing graphs are collapsible.
- Plans remain immutable after creation because the backend exposes no update contract. Project API `13/13`, owning
  TaskPlan Java `8/8` and the production build pass; B02 proof is pending.

### M67-PR1-U07 implemented pending B02

- A bounded owner-scoped public read lists URL ingestion Jobs by Knowledge Document; memory/PostgreSQL use the same
  tenant/owner/document predicate and newest-first order.
- Selecting a document reloads its latest Job so exact-revision refresh/pause/resume survives browser reload. Java/PG
  `6/6`, Knowledge API `2/2` and production build pass without an external fetch.

### M67-PR1-U08 implemented pending B02

- Overview Runtime Sessions use the existing owner-scoped backend page and total, ten rows at a time, with previous/
  next controls. Range changes reset page selection without altering backend state.
- A Session can open Tracing with the same opaque Session ID preselected. Web `4/4`, monitoring Java `4/4` and build
  pass; no export is shown absent an API.

### M67-PR1-U09 implemented pending B02

- Organization Settings integrates authenticated password change; form secrets remain transient, while Java verifies,
  hashes and revokes refresh sessions. API `5/5` and Identity HTTP `6/6` pass.
- Chat/Project composer context is honest read-only evidence with no fake attachment plus. Agent sharing is rendered
  non-interactive/unavailable; no client vault or sharing state was invented. Production build passes.

### M67-PR1 complete

- B01: Web `65/65`, Java/PostgreSQL `55/55`. B02: Web `66/66`, Java/PostgreSQL `29/29`. Required skips are zero.
- Final clean Maven is `721/721`; Admin Web is `22/22`; tenant/Admin builds, package, architecture and all six Compose
  variants pass. Repository boundary, generated-file and credential scans pass.
- The remaining unavailable controls have no authorized public contract and are labeled honestly. Live external
  Provider/MCP/GitHub/S3/telemetry/runsc acceptance was not executed.

### M68-PR1-U01 implemented pending final gate

- New Provider Model forms use a tested `200000` context default matching Inference and AgentVersion new-record
  fallback. Explicit user-entered values remain unchanged; existing records are not rewritten.
- Focused Model Web `5/5`, owning Java/HTTP `15/15` and production build pass.

### M68-PR1 complete

- New Provider Model and AgentVersion defaults are `200000`; explicit inputs and existing immutable records stay
  unchanged. Full Web `67/67`, Maven `722/722`, production build, package, architecture and diff checks pass.

### M69-PR1 complete

- Provider Model test now posts the opaque Model ID in bounded JSON, so IDs such as `ZHIPU/GLM-5.3-Flash` and
  `vanchin/deepseek-v4-pro-0813` are not rejected as encoded URL slashes. The legacy simple-ID route remains.
- Focused Web `4/4`, Java HTTP/Application `9/9`, full Web `67/67`, Maven `723/723`, production build, package,
  architecture and diff pass. No live Provider call or credential read was performed.

### M70-PR1 complete

- Added the selected 1024×1024 deep-space brand source below 1 MB plus a 64×64, 7 KB browser favicon. The HTML head
  now references the same-origin static asset; no page geometry, navigation or API behavior changed.
- Web `67/67`, production build, built/served asset SHA, healthy local 8080 container, architecture and diff pass.

### M73-PR1 complete

- Agent Web exposes the existing tenant DELETE soft archive as an explicit delete action. Confirmation states that
  immutable versions and historical Runs remain; success reloads authoritative resources and never retries DELETE
  when only the read refresh fails.
- Agent API `7/7`, full Web `67/67`, production build, healthy local deployment, architecture and diff pass. Backend
  and schema are unchanged; no real Agent was deleted during verification.

### Post-M75 current Agent configuration alignment

- Agent Web now consumes Organization Agent access projections and the single current configuration. Direct saves are
  immediately effective; cross-owner ADMIN/MEMBER saves display `PENDING_APPROVAL`, and the current OWNER can inspect
  and decide the exact temporary proposal. Backend revisions remain hidden concurrency tokens rather than visible
  version-like counters. The approval entry and right-rail status exist only for a current `PENDING` edit; terminal
  records are not presented as history. Stale decisions refresh authority without retry.
- Removed all AgentVersion review/publication/schedule client contracts and controls. Project Intake, PlanStep
  assignment, whole-Plan execution and Handoff now send primary/Reviewer Agent IDs, while Java resolves and snapshots
  the effective current configuration at Run admission. Agent deletion remains a confirmed creator-scoped backend
  soft archive with an authoritative list reload.
- Focused Agent/Project Web APIs pass `19/19`, full Web passes `64/64`, and the production build passes. Backend Agent
  HTTP passes `9/9` and assignment HTTP passes `1/1`; five unrelated Project HTTP negative tests still expose the
  existing missing-header `500` versus expected `400` mapping defect.

### Project navigation hierarchy correction

- Replaced the nested management-card sidebar with a compact Codex-style navigation tree. The Project/default root
  and repository roots are peer folder rows rather than parent/child folders; each root contains only its own
  single-line Conversations. Repository roots prefer their Source display name, selected Conversations use a full-row
  highlight, create actions live on root rows, and hover/focus delete uses the confirmed canonical Conversation DELETE.
- Collapse state is transient browser presentation only. Project, ProjectDirectory and Conversation IDs remain
  backend-owned, switching Projects clears prior context immediately, and late context responses cannot replace the
  newly selected Project.
