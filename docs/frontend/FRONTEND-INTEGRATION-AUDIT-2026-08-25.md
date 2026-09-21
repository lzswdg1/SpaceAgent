# SpaceAgent Frontend Integration Audit

## New Chat conversation Agent preselection repair (2026-09-16)

The blank Chat workspace now exposes an ACTIVE Agent selector before a Conversation exists. Its
selection is transient presentation state until the user sends the first message; the canonical
Conversation create contract persists that exact Agent. The header and composer route show the
same preselected Agent, while existing Conversations continue using the persisted Agent switch
endpoint. Missing/archived Agents still fail closed and are never silently substituted for an
existing Conversation.

The production incident was not missing data: the owner-scoped Agent summary endpoint returned200
with the user's ACTIVE Agent, while the fresh database contained no Conversation. The previous UI
rendered its selector only after Conversation creation and silently chose the first Agent during
send, leaving the blank screen labeled “No Agent.” This repair adds no API, persistence or backend
authority. Web149/149 and production build pass. The isolated real-component conversation-settings
fixture passes19 assertions at1440px EN/light,19 at1100px ZH/dark and21 at390px JA/light, including
pre-create selection and synchronized header/composer evidence. No real Conversation, Provider or
Tool effect was created during deterministic validation.

## System audit performance and bounded resource lists (2026-09-15)

Chat/Project share a memoized variable-height message window. Offscreen historical Markdown AST/DOM
is released without discarding messages/cursors; live output stays mounted. Height compensation is
only for immutable history above the viewport, never growing live/partial output. Completed Markdown
blocks remain stable; document-wide reference definitions fall back to whole-document parsing.
Raw HTML, remote images and unsafe URL protocols remain disabled.

Coding Jobs now use completion-scheduled serial polling, abort/scope cleanup, hidden-page skipping
and all-terminal stopping. No mutation/effect is silently retried. Agent lists use offset/limit
(default/max100); selectors request summary=true without systemPrompt; the configuration index
pages full editable records on user request. Source lists are bounded; selected roots outside the
first Source page resolve their exact tenant/Project-authorized Source instead of choosing another.

Web147/147 and Admin Web34/34 plus both builds PASS. Isolated real-component browser113 assertions
at1440 EN/light,1100 ZH/dark,390 JA/light PASS; screenshots inspected. Fixtures were aligned with
query parameters/windowed DOM, while fixed-position streaming checks remain. Backend final gates
are in SYSTEM-AUDIT-REMEDIATION-2026-09-15.md. Admin frontend source unchanged; no real user edits,
model/GitHub calls or service activation. Source contracts require coordinated activation.

## Real Skill registry and preparation evidence (2026-09-15)

Agent Skill controls now use organization-scoped `/api/v1/skills`, not the generic capability
catalog's intentionally empty list. They support text creation/import, owner-only instruction
editing, exact draft publication recovery, full content preview and explicit current-content
binding. Missing dependencies disable additions but never auto-enable Tools; selected historical
bindings remain visible/removable. User-facing Agent save/OWNER approval is unchanged.

Tracing adds an explicitly requested hash-only Skill evidence read for the selected Run. Loading,
failure, NO_EVIDENCE and CONTEXT_PREPARED are distinct; preparation is not model-compliance proof.
No automatic real-model probe, host Skill scan or script execution. Contract/recovery/limits and
verification: `docs/operations/SKILL-USAGE.md`. Web142/142 and build, backend12/12, Skill browser45,
existing Tool browser54 and architecture/diff PASS. Source only; business services not activated.
No new dependencies; lazy Agent9.51KB gzip, Tracing2.92KB, main115.50KB.

## Agent tools inline grid correction (2026-09-15)

User clarified that tools must be directly clickable side-by-side, not a long column relocated into
a drawer. This supersedes the drawer design below. Tools now fill the full integration card using
an adaptive grid (210px minimum cards; multiple columns when space permits), compact descriptions,
checkboxes and visible risk badges. Clicking a card's non-interactive area toggles once; expanding
technical details does not toggle. Search/category/selected/available filters and removable selected
chips remain inline. Empty Skills no longer reserve half the page. Long catalogs have a bounded
grid scroll region; there is no modal or extra opening click. Draft save/approval/readonly behavior
is unchanged. Obsolete drawer CSS, controls and translations were removed.

Proof: Web139/139, build and architecture/diff PASS; isolated actual AgentPage browser fixture
passes18 assertions at1440px EN/light,1100px ZH/dark,390px JA/light (54 total), including actual grid
column geometry, card click, detail expansion, exact PUT payload, pending approval and readonly denial.
Screenshots inspected. Existing8080 Web image was refreshed from verified static output only,
preserving the public Admin link at127.0.0.1:5174. Served HTML and Agent JS/CSS hashes match the build;
healthz200. Previous image retained as `spaceagent-m10-pr1-web:before-tool-grid-20260915` for rollback.
Backend/container/database data were not restarted or migrated; no real Agent edits or model calls.
The earlier new monitoring backend APIs still require backend activation; this is not their live acceptance.

## Compact Agent tool picker (2026-09-15)

User authorized replacing the long inline catalog with a compact selected-count/chip summary and
a native modal side drawer. Empty Skills occupy one line; nonempty Skills are collapsible. The
drawer provides literal name/description/ID search, presentation-only categories, All/Selected/
Available filters, real checkbox state, read/write/network/workspace badges and expandable technical
metadata. Availability remains catalog-owned; unavailable selected entries can be removed, not added.
Selected IDs missing from the catalog remain explicit removable chips, never silently dropped.

Selection changes the existing Agent draft only; closing/Escape does not save. The existing PUT,
DIRECT/OWNER_APPROVAL_REQUIRED/READ_ONLY modes, save-time policy checks and approval result handling
remain unchanged. The native dialog traps focus/inerts background and restores trigger focus; it is
keyed by Agent ID, uses an opaque theme surface and keeps long lists inside a bounded scroll region.
Chinese/English/Japanese and desktop/mobile themes are covered. No new package, backend or API.

Verification: Web139/139; production build and architecture/diff PASS. Browser fixture
`WEB_TEST_FIXTURE=agent-tools node scripts/verify-web-interactions.mjs` passes21 assertions at each
of1440px EN/light,1100px ZH/dark,390px JA/light, plus native Escape/focus checks at all three sizes.
Real AgentPage with fake public APIs proves exact save IDs, pending OWNER approval, readonly denial,
unavailable removal, filtering, compact summary, opaque drawer and internal scrolling. Screenshots
inspected. No real Agent mutation or service/container activation. Lazy Agent JS7.94KB gzip
(previous6.85KB), main entry113.90KB gzip (previous113.05KB, translated copy); no dependency added.

## Overview time windows and lifetime Agent usage (2026-09-15)

User authorized tenant frontend integration of the then-current backend contract. Overview now defaults
to rolling seven days and selects `1d` (rolling 24 hours), `7d`, or `all` via the public monitoring
overview/usage/sessions contracts. Top metrics and the ring share the selected range; no browser
date calculation is substituted for backend windows. Existing request-generation fencing remains.

A visible standalone lifetime Agent table reads `/api/v1/monitoring/usage/agents`, unaffected by
range/session-page changes. It preserves server ranking, shows tokens/calls/input/output/cache and
known cost, and exposes the backend 200-row truncation notice. It refreshes independently, has
loading/empty/error/retry states, discards obsolete responses, and never renders failed reads as zero.
Costs distinguish unknown (`—`), explicit zero and positive sub-cent amounts (`<$0.01`); incomplete
cost covers unpriced/UNKNOWN evidence, not a fabricated final invoice. Standalone Knowledge
Embedding remains outside these ModelCallLedger monitoring views and is labeled accordingly.

Chinese/English/Japanese, existing light/dark styling, semantic table headers and keyboard-focusable
bounded table scrolling are preserved. No auth, Java API, Admin Web, pricing or settlement change.
Verification: Web134/134 and production build PASS; architecture/diff PASS. Isolated Chrome fixture
`WEB_TEST_FIXTURE=usage-overview node scripts/verify-web-interactions.mjs` passes48 assertions across
1440px EN/light,1100px ZH/dark,390px JA/light: stale range responses, independent ranking, failures,
empty retry, null/sub-cent cost, truncation, focus and no document overflow. Screenshots inspected.
Fixture data is not live backend acceptance. No real account/Provider calls or container restart.
Frontend/backend source must both be activated before the existing8080 deployment exposes this change.
No new dependencies; lazy Overview JS6.60KB gzip, main entry113.05KB gzip.

## Authenticated heartbeat presence integration (2026-09-14)

User selected HTTP heartbeat and explicitly authorized background tenant-client integration, without
layout changes. The authenticated session hook now renews `/api/v1/presence/heartbeat` after context
restoration/login and every30 seconds, sending only `{clientType:"WEB"}` through the current scoped
AuthSessionManager bearer transport. No identity, credentials, browser activity or counts are persisted
or supplied by the client. Existing one-shot refresh upgrades legacy/expired JWTs; a transient background
refresh failure does not clear auth or abort business streams, while terminal401/403 stops renewal.

Each loop allows one in-flight request, bounds it to10 seconds, ignores stale completions, throttles
network/visibility wake events and cancels timers/listeners on logout, Organization switch or unmount.
Hidden tabs are not deliberately treated as logged out; browser timer throttling/sleep can still let a
lease expire. `pagehide` stops that tab; `pageshow` restarts after bfcache restoration. There is no automatic
leave call when one tab closes or switches: tabs can share a login-session UUID. Other tabs continue
renewing, last-tab closure expires naturally, and successful explicit logout is excluded immediately
by server-side token/refresh revocation. Global user counts remain server-deduplicated across sessions.

Admin overview polls only `/admin/v1/presence` every15 seconds while visible; it does not reload the
whole dashboard or replay layout/count animations. Failures/timeouts clear the stale presence projection
and show unavailable rather than zero. Late/hidden/unmounted responses cannot overwrite newer state.
Initial loading is distinct from NO_CLIENT_HEARTBEATS. Backend TTL is90 seconds (capped by access-token
expiry); visible Admin counts may lag expiry by one additional polling interval.

Verification: Web120/120, Admin33/33, affected Identity/Presence HTTP/PostgreSQL10/10 and both builds pass.
Tests cover cadence, timeout, cleanup, wake throttling, bfcache, independent tabs, token refresh and
transient failure, distinct-user/two-session counts and logout. Existing-user live heartbeat observed;
normal authenticated Admin read returned200 with onlineUsers=1/onlineSessions=1, HEARTBEAT_CLIENTS_ONLY.
That temporary verification Admin session was logged out. No new real test accounts or Provider/MCP
calls. Only the two frontend static images were updated; backend/images/schema/layout were unchanged.
Current host Vite also serves the new tenant source. Main tenant JS113.01 KB gzip, Admin97.03 KB gzip
(97.01 KB with local return-link configuration); no new dependencies.

## Administrator data fidelity and localization repair (2026-09-14)

User explicitly authorized the Admin Web repair; tenant Web stays unchanged. Dashboard now reads
the existing `/admin/v1/dashboard` and `/admin/v1/presence` independently, fences stale responses,
distinguishes failed/missing data from zero and offers explicit refresh. UNKNOWN model/tool effects
are separate; `inference.unknownModelCalls` comes from the model ledger. `runtime.unknownRuns` is
nullable (not a Run lifecycle state); overview readiness is NOT_CHECKED, not a health assertion.
Heartbeat coverage stays partial/not-collected until clients send heartbeats; no tenant heartbeat
was added. Unfinished Runs are labeled persisted status, not proof of live execution.

Removed the retired AgentVersion governance card/client/type and decorative LIVE/VERIFIED/topology
claims. Resources displays a compact inventory, not fabricated CPU/memory/storage consumption.
Admin labels/options/statuses/common audit actions and errors use Chinese/Japanese/English copy;
translated options retain exact API values. List failures cannot present an empty success result.
Organization/member list SQL separators are fixed and exercised via PostgreSQL HTTP tests.

Evidence: Admin Web 28/28/build, Java affected union50/50/package; browser Chinese/dark, Japanese/light,
English and390×844 Japanese/no horizontal overflow. Actual dashboard shows4 unknown model calls and
10 organizations; V1085 and the three affected services activated locally, no tenant image changes.
Full details and retained pre-repair evidence: `ADMIN-UI-AUDIT-2026-09-14.md`.

## Conversation settings in both inspectors (2026-09-13)

Chat and Project expose the same top-level Conversation settings card: persisted name, the Agent
used for future messages, logical Conversation deletion and a link to the existing Agent page.
Project additionally shows the root name read-only, followed by repository/environment controls;
task/plan execution remains an advanced collapsed group. The previous Agent selector inside that
group and Chat's separate selector are removed. Prompt/model/tool/RAG configuration is not copied
into the inspector. With no selected Conversation the card shows an honest empty state. Active
operations lock edits. An unavailable bound Agent is never replaced by an inferred first Agent.

Public contract: `PUT /api/v1/chat/conversations/{id}/title` with `{name}` returns the authoritative
Conversation summary. Existing owner/tenant/write checks apply; only ACTIVE conversations accept
a nonblank title up to 200 UTF-16 units, trimmed before persistence. No migration is required.
Conversation detail/Agent-switch responses additionally include `title`, so deep links preserve
renamed titles. Agent selection continues using `/chat/conversations/{id}/agent` and never writes
Agent definitions. Conversation mutations take the existing row lock to preserve metadata around
reply completion, Agent and active-task changes. Names do not change Project/root/storage identity.

The shared editor is keyed by Conversation ID, retains rejected drafts and applies late responses
only to their addressed record. Header and sidebar names reconcile from responses. On narrow
screens the inspector spans the full grid containing block instead of the zero-width second track,
with a close button inside the overlay. No duplicate manual model/permission editor is introduced.

Verification: 29 focused Conversation/application/HTTP/PostgreSQL/TaskPlan/repository-Chat/approval
tests PASS; Web 112/112/build, architecture/diff PASS. Settings browser fixture passes 50 assertions
across 1440px EN/light, 1100px ZH/dark and 390px JA/light, including save/reload, Agent switching,
late responses, denied saves, missing Agent binding and usable mobile width; existing browser gate
113 assertions PASS. Repeat with
`WEB_TEST_FIXTURE=conversation-settings node scripts/verify-web-interactions.mjs`.
Only platform-server was repackaged/activated (`conversation-settings-20260913`); readiness UP,
8 Conversations/69 messages retained. Vite 5173 serves the new client. No real-account rename,
paid Provider/GitHub acceptance, schema migration or Web/Admin image rebuild was performed.

## Bottom workspace details and route entrance (2026-09-13)

Project roots and Chat conversations now occupy one stable portal host after every primary
navigation item, above the account footer. Available vertical space pushes this region toward
the bottom; long lists have bounded independent scrolling. The list is no longer interleaved
between Project and Agent navigation. Other primary items retain their positions across modes.

Chat/Project main content fades in over 200ms. The workspace list fades/slides 7px over 220ms only
when its first index load is ready; later typing, polling and refreshes do not remount its wrapper
or replay the entrance. The portal target is never keyed/replaced for animation. This uses opacity
and a small list transform, not blur or grid-height animation; reduced-motion disables both effects.
API ownership, stream lifecycle, drafts, manual scrolling and previous Chat control removal remain.

Verification: Web 108/108/build and architecture/diff PASS. Standard browser gate passes 113
assertions across 1440px English/light, 1100px Chinese/dark and 390px Japanese/light. New
`WEB_TEST_FIXTURE=navigation-transition node scripts/verify-web-interactions.mjs` runs with real
animations enabled: 32 assertions plus three reduced-motion checks prove bottom ordering, real
Chat/Project entrance, stable portal/menu geometry and draft preservation. Screenshots inspected.
Only source/HMR updated; no backend, production data or Docker change.

## Chat sidebar simplification (2026-09-13)

Per user request, the portalled Chat rail now contains the conversation list without the redundant
“Workbench / CHAT” header, its plus button or the search box. The unused Chat search state/debounce
is removed, not merely hidden with CSS. Backend search support is unchanged. Pagination, selection,
deletion and the overview's existing new-conversation entry remain available.

## Scroll drawing-cost reduction (2026-09-13)

Scope is manual page scrolling, not model response or streaming throughput. Main/sidebar/composer
surfaces keep their tint, rounded borders and shadows without full-surface backdrop blur. The large
ambient gradient is static and unfiltered; the portalled navigation slot no longer animates blur or
grid rows. Other product animations and all backend/Conversation logic remain unchanged.
ConversationViewport uses a passive scroll listener, coalesces geometry reads with ResizeObserver
into one animation-frame callback and updates React only when jump-button visibility actually
changes. Paint containment is confined to the already clipped conversation viewport. It never
enables auto-follow or changes the user's reading position.

Evidence/limitations: an isolated Chromium static 40-message/769-node page at 1440px/DPR2 sampled
120 rAF intervals per variant, twice, in forward/reverse order (current / without blur / without
motion / without both). Baseline and post-change current variants both had median ~16.7ms, P95
~16.8ms, no interval >25ms. A post-change diagnostic blur-off variant had one 33.4ms interval; there
is no measurable speedup claim. rAF timing is not presented-frame/GPU or user Firefox telemetry.
Independent Firefox 155.0.1 could not load its fresh temporary profile and timed out; no user's
profile/preferences/extensions were changed. The original Firefox symptom is not conclusively
reproduced or attributed. These are conservative cost reductions requiring user-side confirmation.

Repeat the static diagnostic with
`WEB_TEST_FIXTURE=scroll-performance WEB_TEST_WIDTH=1440 node scripts/verify-web-interactions.mjs`.
It uses static local fixtures and no account/model/network business calls. Optional local result
reporting supports the isolated Firefox harness. Before/after evidence:
Ephemeral browser JSON reports from the isolated regression runs; computed backdrop filters and ambient
animation are all `none` after the change. Web 108/108/build, architecture and the 110-assertion
three-size interaction gate pass; screenshots inspected. No runtime/container/backend rebuild.

## Message bubbles and reader-controlled streaming (2026-09-13)

User clarification overrides the earlier near-bottom auto-follow behavior: Chat and Project keep
rendering real SSE deltas, but new content must not move the reading viewport, even at the bottom.
Both now share `ConversationViewport`: ResizeObserver only updates a manual “Jump to latest” button;
it never writes scrollTop. Native scroll anchoring/smooth scrolling are disabled for the transcript.
Only explicit jump and older-history prepend compensation change its scroll position. Jump is a
one-time action, not a sticky follow mode. No Provider buffering, synthetic typing or API change.

The centered transcript is capped at 760px. User messages have one content-sized, right-aligned,
rounded pale-blue bubble (dark-mode equivalent), with bounded width and long-text wrapping; the old
outer card, duplicate inner border and visible USER/ASSISTANT labels are removed. Articles retain
translated accessible author labels. Assistant Markdown remains in the shared reading column.

Verification: Web 108/108 and production build PASS; architecture/diff checks PASS. The repeatable
browser gate passes 37 assertions at 1440px English/light, 37 at 1100px Chinese/dark and 36 at 390px
Japanese/light. It measures both Chat and Project bubble geometry, centered width and fixed scrollTop
during initial, bottom-position, manually scrolled and post-jump streaming bursts. Final screenshots
were inspected. A prior mobile browser protocol timeout passed on isolated rerun and then the full
matrix; no product exception was identified. Backend, user data and Docker images are unchanged;
the existing 5173 Vite service serves these source changes.

## Interaction audit repairs (2026-09-13)

The six recorded findings are repaired without changing Java/public contracts or the
accepted glass/neutral design:

- Chat and Project pin the admitted Run ID and AbortController to the active stream operation.
  Stop calls `/api/v1/chat/runs/{exactRunId}/cancel` before aborting transport; cancellation failure
  preserves the connection and permits an explicit retry. Repeated stop clicks share one request.
  Conversation/root selection and creation are temporarily locked during local streaming, with
  translated explanatory copy. They unlock after completion/stop; pane folding remains available.
- Portalled Chat/Project navigation overrides legacy mobile `display:none` and 62px nav height.
  The collapsed desktop rail wins over 701–1200px breakpoint widths and releases its gap/column.
- Overview corrects a page number only after authoritative pagination data arrives, preventing
  loading placeholders from resetting page 2/3 to page 1.
- Explicit Chat/Project conversation links resolve through the existing owner-scoped detail API,
  including conversations beyond the first 100 summaries. Scope/status/root mismatch stays an error,
  never a silent switch to another conversation. An absent detail title uses a neutral translated
  label until a summary is available. Generation guards discard late initial-link responses after
  explicit selection; retained out-of-page details refresh from Java and are cleared on deletion.
- Chat/Project Enter handlers share a composition guard (`isComposing`/229). IME confirmation and
  Shift+Enter do not submit; ordinary Enter still does.

Verification: Web 108/108 tests, production build, architecture and diff checks PASS. Committed
`node scripts/verify-web-interactions.mjs` runs real React/CSS with fake public APIs/SSE in an isolated
Chrome profile: 1440px English/light (15 assertions), 1100px Chinese/dark (15), 390px Japanese/light
(14). It exercises paging, actual column geometry, mobile root/history visibility, exact old/invalid
links, Project root binding, composing Enter and pinned cancellation. Screenshots were inspected;
no production browser profile, paid Provider/GitHub call or live business mutation is used. System
IME compatibility beyond synthesized composition events is not claimed. Set `CHROME_BIN` if Chrome
is not at the macOS default path. Browser artifacts use a fresh temporary directory per run.

Bundle: main entry 111.57 KB gzip (previous 111.47); Project 44.87 KB gzip (previous 44.06).
Existing host Vite on 5173 serves the updated source. No backend restart or Docker rebuild was
required/performed; the 8080 container is not claimed to contain this source-only update.

## Authenticated platform glass redesign (2026-09-13)

Scope: tenant `/app/**` only, based on the accepted liquid-workspace prototype. Marketing, entry,
login/register UI, authentication transport, administrator UI and Java APIs remain unchanged.
React 19/TypeScript and the existing lazy feature owners remain; prototype scripts/data are not imported.

- The shell owns only navigation visibility, theme and a React portal target. Project and Chat keep
  their existing state/API/SSE hooks while rendering navigation into that target. Both inspectors
  remain independently collapsible; hidden navigation/inspectors are inert. Narrow screens use a
  left drawer with a backdrop and persistent restore control. Project root/conversation navigation
  uses `@rc-component/tree` (Ant Design ecosystem); actions retain existing owner handlers and paging.
- Shared scoped glass tokens apply to all 11 tenant pages. Existing brand typography and the existing
  64px favicon/logo are reused; public landing/auth styles and BrandLockup are unchanged.
- Overview resumes real `/api/v1/chat/conversations` summaries through validated route selections.
  The new-conversation entry opens a blank Chat composer without creating a record until the user
  requests work. Usage calls the existing monitoring overview/usage/realtime/sessions endpoints,
  including supported `today` (UTC), `7d`, `30d` ranges. Input, output and cache tokens remain separate;
  costs remain backend USD evidence, with null/incomplete pricing explicitly visible. Old usage
  detail and runtime stop controls remain in an expandable management section. Range fetches are
  generation-fenced so stale results cannot overwrite a newer selection.
- Agent direct binding is selected from real provider/model candidates, with separate search text;
  no Provider UUID needs to be typed. Existing `chooseModel`, pool exclusion, write modes, approval,
  deletion and editor fencing remain in use. Search resets when the selected Agent changes.
- No new public endpoint, model call, OAuth grant, Tool/Git side effect or browser-owned business state.

Validation: Web 98 tests pass; TypeScript, production Vite build, architecture check and diff whitespace checks pass. The entry JS is 111.47 KB gzip; the lazy Project chunk (including the tree library) is 44.07 KB gzip. The existing 7 KB logo avoids adding the 983 KB source bitmap to the platform load. Real-account read-only
browser checks on host Vite `127.0.0.1:5173` against existing platform-server `9000` covered all 11
routes, real usage/partial price evidence, Project and Chat independent folds, existing Markdown
history, resource/configuration loading, dark/light and 390px navigation/composer layout. Isolated
real-component fixture `/tmp/spaceagent-platform-glass-qa` additionally checked unchanged approval
and partial-answer presentation across navigation folding, named model choices, English navigation
and pauseable ring animation. No paid Provider/GitHub acceptance or live business writes were run.
Only host Vite was activated; existing Docker services were not rebuilt or restarted.


M77 client scoping/paging: tenant changes remount the authenticated workspace and invalidate old
requests; late Agent saves and Provider tests are generation-scoped. ModelPool selection clears direct
bindings. Conversation queries support scope/query/page and summaries carry activeTaskId/userId, avoiding
per-row Project detail fetches. Chat/Project expose load-more controls and the authenticated
`/chat/conversations/{id}/messages/page` sequence cursor for older history. The legacy messages list is
unchanged. Admin DELETING organizations expose explicit physical-cleanup authorization with the existing
reason/recent-MFA workflow. Source checks pass; combined browser/runtime activation remains M77 U04.

Repository Ask streaming: Tool-calling round preambles are no longer published as answer deltas.
Runtime retains request-local completed-call/evidence history with fair, explicitly marked excerpts,
caches identical read calls within the turn, and switches to synthesis on no progress. Final text
remains on the existing delta/done contract. Provider `length` completion triggers at most two
tool-free, separately ledgered continuations; the concatenated answer is persisted and token totals
include these calls. If the cap is still reached, the answer explicitly says it is incomplete.
This does not rewrite old messages, change configured token limits, or grant additional Tools.

Project branch visibility: repository branch listing filters reserved `spaceagent/` and
`spaceagent-intake` execution refs at both the Git adapter and public workbench application boundary.
User-facing Workspace HTTP metadata redacts `branchName`; environment metadata uses the source
base ref, not the execution branch. React independently filters stale API/current selections and
labels workspace cards with the base ref. Internal domain state and bridge execution commands remain
unchanged; no ref is deleted, checked out or pushed by this visibility repair.

Audit date: 2026-09-12 (current Agent configuration contract alignment)

Current status: the tenant Web no longer calls removed AgentVersion/governance routes. Agent and Project controls use
the current Agent configuration contract, organization write modes and revision-fenced configuration-change decisions.
Focused Agent/Project Web APIs pass `19/19`, full Web passes `64/64`, and the production TypeScript build passes.
Backend Agent HTTP passes `9/9` and Project assignment HTTP passes `1/1`; the broader Project HTTP class has five
pre-existing missing-idempotency-header assertions returning `500` instead of expected `400`, recorded separately
from this frontend alignment.

Scope: active React application under `apps/web/src`, its typed API clients, and the
matching public HTTP adapters in `apps/platform-server`. Prototype HTML files are design
evidence and are not counted as active product components.

The independent administrator client is tracked separately in
`docs/frontend/ADMIN-WEB-IMPLEMENTATION-PLAN.md`; it does not reuse tenant authentication or add
business authority to this application.

M49 Admin Web audit: the M48 Admin V4 singleton is now integrated. The visible security surface
shows exactly one SystemAdministrator, its bounded Session list, protected current Session,
reasoned/recent-MFA revocation of another Session and current-password/recent-MFA recovery-code
rotation. Multi-principal create/suspend/restore/credential-recovery controls and client methods
were removed. Offline break-glass remains intentionally operator-only and has no browser control.

Status meanings:

- **Integrated**: the visible interaction calls an authoritative backend contract or is an
  explicitly local presentation preference such as theme/language.
- **Partial**: the visible component works, but represents only part of the named product
  capability.
- **Display only**: no mutation/query is expected from the current component.
- **Unavailable**: the UI implies an action but has no handler/contract, or is deliberately
  disabled pending a later contract.
- **Missing surface**: the backend capability exists but no active React page/control exposes it.

## Route-level result

| Surface | Status | Current authoritative integration | Remaining gap |
| --- | --- | --- | --- |
| Authentication | Integrated | Register, login, refresh, logout, authenticated password change with refresh-session revocation, current user and Organization list/switch use `/api/v1/auth/*`, `/users/me`, and `/organizations` | No secure forgot-password/reset contract exists; the previous no-op button has been removed |
| Application shell | Integrated | Navigation, Organization switch, logout; language/theme are intentionally local preferences | None for the current shell contract |
| Overview | Integrated | Overview, usage, realtime and paginated Session monitoring APIs; 7D/30D reload real data; previous/next uses backend total/pageSize; each Session opens matching Tracing evidence; Organization administrators can confirm-stop one visible Runtime Session or active Sessions on the loaded page | No export contract/control exists; batch stop intentionally covers only the loaded page |
| Chat | Integrated | Agent/Conversation list, search/create/history/delete, persisted Agent switch, real Provider/Runtime SSE, Tool-result natural-language synthesis and ephemeral collapsible reasoning | Composer context badges are labeled read-only because no attachment/context-selection contract exists; reasoning is current-stream-only and inspector does not reload historical Run evidence |
| Project | Partial | Project create/edit/archive; ProjectDirectory create/select/archive; directory-grouped Conversation/SSE; directory-bound Workspace; Task intent and bounded multi-step TaskPlan/DAG creation; server-derived Plan-default/per-Step assignment using primary/Reviewer Agent IDs and the primary Agent current ModelPool; GitHub MCP Source; current-Agent Intake; reviewed whole-Plan execution; revision-fenced pause/resume/cancel plus redacted control evidence; durable Execution/Coding Job/Handoff/Recovery evidence; Chat approval and UNKNOWN continuation | Created TaskPlans are immutable because no update contract exists; Local Bridge remains CLI-owned; real GitHub/Provider/Sandbox execution remains operator-dependent |
| Agent | Partial | Organization Agent discovery exposes `DIRECT`, `OWNER_APPROVAL_REQUIRED` and `READ_ONLY`; create and direct save update one immediately effective current configuration; cross-owner ADMIN/MEMBER save returns `202 PENDING_APPROVAL`. Only while a proposal is pending does the UI expose an approval/status entry; the current OWNER can inspect and decide it, while stale `409` reloads authority without retry. Creator-scoped delete uses backend soft archive and authoritative reload. Provider/ModelPool binding, budgets, permissions, RAG/Knowledge, Runtime Tool catalog, sandbox evidence and owner-scoped Agent API-key lifecycle remain integrated | Sandbox mode is deployment-owned and read-only. No executable Skill is advertised. Terminal change proposals are not rendered as browser history; backend erases proposal content and retains only bounded evidence. No Agent version-management UI or API remains |
| Automation | Partial | Agent schedule list/detail/create/revision-edit/pause/resume/archive, confirmed idempotent manual trigger and bounded execution evidence use `/api/v1/agents/{agentId}/scheduled-tasks/**` | Event Trigger definition/version lifecycle and approval-resume occurrence management have no tenant React surface yet |
| Knowledge | Integrated | Local text import, pasted text processing, bounded public-HTTPS URL ingestion with one-time/periodic refresh, owner-scoped URL Job rediscovery after reload, revision-fenced pause/resume, Chunk evidence, retrieval and delete use public Knowledge APIs | Supported local files remain text/Markdown/JSON/CSV only; live URL/Embedding behavior is environment-dependent |
| Memory | Integrated | USER/PROJECT/TASK consolidated recall, bounded pending-candidate list, candidate proposal, confirmed accept/reject and Task consolidation/promotion evidence use scope-authorized `/api/v1/memory/**` | Scope discovery reuses explicit Project/Task IDs; automatic message evaluation remains Runtime-owned and intentionally has no manual browser control |
| Model resources | Integrated | Provider CRUD, secret rotation, connection test, slash-safe JSON model test, model CRUD, ModelPool member/status lifecycle, Organization budget policy/usage and immutable effective Model price versions; new model context defaults to 200,000 in React/Java | Model tests are real paid Provider calls when invoked; deterministic frontend tests do not invoke them |
| MCP marketplace | Integrated/External blocker | Versioned catalog and pinned installation, connect/edit/revoke/disable, specialized GitHub plus pre-registered generic OAuth callbacks, explicit remote qualification, capability snapshot and bounded health observations use `/api/v1/mcp-marketplace/**` | Real OAuth and qualification require operator profiles and reachable approved MCP hosts. Registry synchronization/review remains a separate SystemAdministrator surface; sync never auto-installs or executes metadata |
| Tracing | Integrated read-only | User-scoped trace list/stats/detail and local filters | No stop/export/recovery controls; this page is evidence-only by design |
| Organization settings | Partial | Current Organization/member list, create/switch, member add/reactivation/role/remove, invitation create/list/revoke/accept, explicit OWNER transfer, safe non-current leave and authenticated password change use public APIs with confirmation/authoritative results | Agent sharing is explicitly non-interactive and a general secret vault remains absent because neither has an authorized tenant public contract; Provider/MCP secrets stay in their owner surfaces |

## Visible control integrity result

- No enabled action in the audited Chat, Project or Organization surfaces lacks a handler/public contract.
- Chat/Project context badges are explicitly read-only and the misleading attachment-style plus marker is removed.
- Organization Agent sharing is non-interactive `UNAVAILABLE` evidence, not a disabled action pretending to save.
- Account password change uses the public Identity contract. A general Provider/MCP credential vault remains absent;
  those secrets stay within their existing backend-owned Model Resources and MCP connection flows.

## Backend capabilities without an active frontend surface

- Local Bridge registration/heartbeat/completion controls, which intentionally remain CLI-owned.
- Generic governance approval-request controls outside the existing AgentVersion workflow.
- Event Trigger definition/version and occurrence approval-resume controls.
- Dedicated credential vault UI.

## Recommended implementation order

1. Add Agent sharing and a general secret vault only after owner modules publish authorized tenant contracts.
2. Add Event Trigger/occurrence management in a later promoted milestone; keep Local Bridge browser controls absent.

## Fixes included with this audit

- Project navigation now renders only a flat list of physical-backed logical roots. Project ownership
  containers are not extra folders. Each repository or named empty managed root owns its Conversations
  directly. Root create/rename/prepare/delete use `/api/v1/project-roots`; deletion is confirmed and
  retains physical source and Workspace code. Default roots can be removed without being recreated
  by legacy Conversation fallback. Frontend root listing includes roots across accessible Projects.

- The branch selector can now resolve a sole imported repository even from the default Project
  directory; multiple repositories require explicit selection. Branch preparation uses that source
  ID and adds/selects the resulting repository Conversation, including newly created conversations.
  Source-keyed branch loading prevents old responses or pending selections from crossing repositories.
  Loading, absent source, unsynchronized branches, busy and failed states have visible explanations.

- Project is a conversation-first coding workbench: environment/source branch/commit/change summary
  replaces the always-expanded operations inspector; task/plan controls remain in a collapsible
  advanced area. Optional repository browsing uses authenticated Project read APIs, bounded folder
  listing and text-only file previews. Git metadata and traversal are rejected by Java.
- Source branches are read from the authorized local mirror. Switching prepares/selects another
  isolated Workspace and preserves the former workspace/changes. The displayed branch, selected
  Conversation Task and subsequent read Run move together. Existing workspaces are not checked out
  in place. Branch inputs on first preparation allow selecting a non-default source branch.
- AI coding submissions explicitly confirm the goal and distinct reviewer, enable configured Coding
  tools through the Agent save/approval contract, and create/approve/dispatch a real TaskPlan. Durable
  coding state and pending approval appear in the conversation; optional files show the coding job's
  Workspace once available. Ordinary streaming Q&A remains selectable. Remote push/PR publishing are
  not advertised as implemented operations.

- Installed GitHub OAUTH2 connections expose reauthorization for ACTIVE, DEGRADED, ERROR and
  PENDING_VALIDATION states using the existing same-connection GitHub OAuth begin/callback contract.
  PENDING_AUTH retains Continue OAuth; revoked/non-OAuth connections do not offer this action.
  Repository preparation recognizes backend reauthorization codes and links to MCP Marketplace in
  another tab, preserving the current draft. No OAuth or Workspace request is automatically retried.

- Sending from a default Project directory now also opens repository preparation when a READY
  source is already imported and no Workspace is selected. The draft remains intact and no ordinary
  Chat request is sent before preparation. This covers imported sources whose old directory was archived.

- Project now shares the application-navigation collapse behavior with Chat and has independently
  collapsible project-list and inspector panels. CSS changes visibility/width without unmounting
  the conversation, losing IDs, or aborting its stream. Project history hydration is guarded while
  sending, and resize/content observers follow new text only while the reader is near the bottom.
- Chat and Project assistant messages share `react-markdown` + `remark-gfm` rendering for CommonMark
  and GFM headings, lists, tables, links and code blocks. React AST rendering uses no raw HTML parser
  or `dangerouslySetInnerHTML`; raw HTML is skipped, URLs allow HTTP(S)/fragments only, and images
  render their alt text without automatic remote requests. Historical messages are memoized and
  live parsing uses deferred rendering. The renderer adds about 48 KB gzip to the shared lazy
  Chat/Project chunk; initial application bundle growth is under 1 KB gzip. CSP remains unchanged.

- Project repository chat now has an explicit preparation form. It selects a READY imported source,
  reuses or creates its ACTIVE root directory and Conversation, binds a nonterminal Task and provisions
  a managed Workspace through public APIs. File-list/read enablement uses the normal Agent PATCH and
  stops on pending OWNER approval. Existing default conversations remain intact. Refresh rediscovers
  the selected directory/Task Workspace; duplicate or non-READY Workspaces are not guessed.
- Chat HTTP/SSE accepts an optional `workspaceId`. Java pins a read-only repository attachment in a
  durable Runtime checkpoint, validates current scope on every read, and permits a bounded sequence
  of file-list/read calls before final synthesis. Writable PlanStep authorization is unchanged.
  The attachment requires configured Agent read tools; frontend state cannot grant runtime access.

- Project navigation now follows a compact Codex-style hierarchy: a Project/default directory and
  each repository root render as peer root folders; each root owns only its indented Conversations,
  which are single-line selectable leaves. Repository roots are never shown as children of the
  Project/default root. Repository labels prefer the authoritative Source display name. Conversation
  rows expose a hover/focus delete action that selects the exact Conversation, opens the existing
  confirmation, then calls the canonical delete contract.
  Project/directory collapse is browser-only presentation state; all selected IDs and mutations
  still use the authoritative ProjectDirectory and Conversation contracts.
- Project composer textarea is focusable even before a Project exists. Send remains disabled
  until both a Project and Agent are available, preserving backend ownership requirements.
- Overview now renders the backend Session projection with Agent/provider, activity, duration and
  token evidence. Single and current-page batch stop require confirmation, call the authoritative
  Runtime cancellation boundary and reload all monitoring evidence; the browser never marks a Run
  stopped locally or claims Sessions outside the loaded page.
- Overview Session pagination now renders the complete requested backend page and uses authoritative totals. Trace
  navigation carries only the opaque Session ID and preselects matching evidence in the existing Tracing route.
- The authenticated sidebar brand now shows only the `SpaceAgent` wordmark; `SA/APP` and the
  motto were removed from that location.
- Authenticated password change now verifies the current password through Identity, never stores password form values
  in browser state and reports backend refresh-session revocation. The UI's minimum length copy now matches 10.
- Chat and Project no longer show a fake attachment plus in the composer; existing route/model badges are labeled as
  the active backend context. Agent sharing is plain unavailable evidence until a real public policy exists.
- Chat Conversation search now filters by title and Agent name.
- Project Conversation Agent selection now uses the existing persisted, owner-scoped switch
  contract and clears stale current-run summary state after a successful switch.
- The misleading Forgot password button was removed until a verified recovery contract exists.
- Agent Tool/Skill configuration now comes from `/api/v1/tooling/capabilities`; Java rejects
  unknown IDs, the UI exposes executable Tools, and an empty Skill catalog is shown truthfully.
- Knowledge Search can no longer be saved without both enabled retrieval and at least one Knowledge
  binding. For every Chat inference round, Java gives the model only the configured Tool subset that
  the exact immutable Run snapshot can execute: network Tools require network permission, Knowledge
  Search requires active bindings, and Workspace Tools require a Project/Task/Workspace-bound Run.
- Project can create Tasks and bind/clear the current Conversation active Task.
- Knowledge online import now creates a bounded URL ingestion Job after the source record. Java—not
  the browser—fetches public HTTPS content, validates redirects/size, parses and embeds it, then
  atomically activates Chunk evidence. The UI polls only the current in-memory Job while visible
  and sends exact-revision pause/resume commands; a failed second stage retains the source for retry.
- Knowledge URL Job controls now survive reload through a bounded tenant/owner/document-scoped list projection. The
  page selects backend newest-first evidence and never guesses a Job ID or lifecycle state.
- Project now exposes legal Task lifecycle actions and TaskPlan propose/approve/activate/
  complete/cancel transitions, with direct child Task validation owned by Java.
- Project Task intent editing now persists title, goal, description, constraints and acceptance
  criteria through the canonical partial-update contract; terminal-state rejection stays in Java.
- Waiting/blocked Project Coding Jobs can now create a durable same-directory Handoff into another
  Conversation and published target AgentVersion. The source AgentVersion is pinned as the distinct
  Reviewer, the backend captures the Recovery Package, and the browser renders returned evidence
  without authoring Runtime state.
- Project Plan execution creation now uses the M57 durable Execution endpoint. The inspector reads
  its redacted control projection and sends pause/resume/cancel with the exact current revision;
  pause/cancel require an explicit bounded reason, transitional states refresh from Java, and the
  browser never treats a request acknowledgement as proof that a safe boundary was reached.
- Project PlanStep assignment controls now select published primary and distinct Reviewer AgentVersions plus the
  primary version's ModelPool for Plan-default or per-Step override. React sends no capability/configuration hash;
  Runtime derives both from owner APIs and returns immutable revision/evidence for display.
- Project TaskPlan drafts now author multiple Steps and key-based dependencies through the canonical create contract.
  Existing plans show a collapsible dependency graph; React does not offer post-creation edits absent a real API.
- Project Source/Workspace controls now reuse ACTIVE GitHub MCP authorization, perform explicit
  repository discovery/import, and provision/archive Java-owned Workspaces without accepting a
  token, local path, Bridge Token or checkout credential in the browser.
- The existing Agent Tool card now consumes all M33 backend catalog metadata, prevents unavailable
  additions, shows read/write, network and Workspace requirements and keeps Java as the save/runtime
  authority. No navigation, card order or page geometry changed.
- Agent API keys now have an owner/admin-only metadata list, scoped creation and explicit revoke
  flow. The raw key is rendered only from the create response, remains in component memory and is
  discarded when the dialog closes; no Web Storage, URL or subsequent list response contains it.
- Agent management now follows the backend's versionless current-configuration contract. The UI
  lists all Organization-visible Agents with their authoritative write mode, applies direct saves
  immediately and exposes cross-owner saves only while their temporary proposal is `PENDING`.
  Approval/status controls disappear when no pending edit exists; terminal records are not rendered
  as configuration history. Internal Agent/request revisions are deliberately not displayed as
  user-facing counters. Only the current Organization OWNER receives approve/reject controls;
  decisions still send the exact backend revision, and conflicts reload backend state without blind retry.
- All removed AgentVersion review, publication, rollback, deprecation and activation-schedule API
  calls and controls are absent. Project Intake, execution assignment and Handoff now send Agent IDs;
  Java resolves and snapshots the effective current configuration for each admitted Run.
- Agent Execution Boundary now reads the backend sandbox projection and distinguishes OCI-container
  configuration from in-process compatibility without exposing or mutating worker configuration.
- Chat and Project now relay actual Provider content/reasoning deltas. Tool JSON is kept as Runtime
  evidence and a second ledgered inference produces the final natural-language Assistant response;
  the current response's reasoning is dynamically timed/collapsible and is not durable Memory.
- The independent Super Admin overview now reads bounded AgentVersion governance counts through
  Admin Server audit and a dedicated short-lived service scope. The browser receives no prompt,
  version body, reviewer identity or credential material.
