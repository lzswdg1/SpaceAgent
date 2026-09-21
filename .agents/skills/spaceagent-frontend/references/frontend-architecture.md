# Active frontend architecture

Use this reference for implementation work under `apps/web`.

## Sources of truth

- Active product source: tenant client `apps/web/src`; independent administrator client
  `apps/admin-web/src`.
- Visual and interaction baseline: `apps/web/prototypes`, plus the accepted behavior already present
  in React. Prototype HTML is not imported into production and does not define backend behavior.
- Product/API integration inventory: `docs/frontend/FRONTEND-INTEGRATION-AUDIT-2026-08-25.md`.
- Delivery history and intentional gaps: `docs/frontend/FRONTEND-IMPLEMENTATION-PLAN.md`.
- Public behavior and authority: current Java HTTP/Application APIs and their tests, never copied
  frontend DTOs from retired services.

`copy/browser` is an isolated private reference snapshot. Do not import from it, install its
dependencies, place active Web code inside it, or include it in public-source exports.

## Stack and composition

The client uses React 19, TypeScript strict mode, Vite, Vitest and CSS. Keep the dependency graph
small; prefer browser APIs and current React primitives when they solve the problem clearly.

`App.tsx` owns public entry/auth composition, native History API routing and lazy authenticated
page boundaries. `ApplicationShell` owns the stable authenticated navigation, Organization context,
language/theme controls and content frame. Product features live under `src/features/<feature>`:

```text
<Feature>Page.tsx       rendering and transient interaction state
<feature>Api.ts         typed public route construction and payloads
types.ts                feature HTTP/view types
<feature>.css           feature-scoped layout and presentation
*.test.ts               deterministic API/pure-state regression coverage
```

Shared primitives belong in `src/components`, app composition in `src/app`, and transport/SSE
mechanics in `src/lib`. Move code upward only when at least two real consumers need the same
behavior; do not create generic abstraction layers in anticipation of future pages.

`apps/admin-web` remains a separate package and deployment. Its typed client calls only
`/admin/v1/**`; it does not import tenant feature state or expose Admin routes inside `apps/web`.

## State and authority

- Keep local form, selection, modal, collapse, animation and in-flight state in the owning component.
- Theme, language and explicitly approved presentation preferences may use local storage. Credentials,
  authorization decisions and business records may not.
- Browser authentication uses the backend's HttpOnly refresh-cookie flow and an in-memory access
  token. Do not reintroduce access/refresh tokens into localStorage, sessionStorage, URLs or logs.
- Reload authoritative records after successful mutations or update the matching typed local view
  from the returned server DTO. Never invent IDs, lifecycle transitions, costs, Run status or
  authorization success.
- Organization switching replaces the authenticated context. Clear or reload tenant-scoped feature
  state rather than carrying records across Organizations.

## Public HTTP integration

- Send requests through the injected authenticated `request`/`openStream` boundary. Do not call a
  Provider, MCP server, SearXNG, GitHub or Sandbox worker directly from the browser.
- Use the `{ success, data, message, code? }` API envelope and `ApiError`. Keep user messages bounded
  and safe; backend error codes are evidence, not permission to expose raw response bodies.
- Encode every dynamic URL segment with `encodeURIComponent`. Use JSON bodies and explicit methods.
- Parallelize independent bounded reads with `Promise.all`; avoid waterfall and per-row N+1 loading.
  Prefer backend pagination/bulk contracts over unbounded browser fan-out.
- A visible mutation needs progress, duplicate-submit prevention, success reconciliation and an
  actionable error state. Destructive or context-changing actions require explicit confirmation.
- Do not create UI for an internal endpoint. If a required public contract is absent, mark the
  surface unavailable in the integration audit and route the contract work appropriately.

## Streaming and long-running interaction

Chat and Project consume same-origin SSE through `src/lib/sse.ts` and the authenticated stream
boundary. Preserve these invariants:

- one AbortController per active user operation, with cleanup on cancellation/unmount;
- separate ephemeral `reasoning_delta` from persisted Assistant `delta` content;
- reasoning is current-stream presentation and must not be written to Conversation or Memory;
- `done`/terminal evidence closes the live UI; errors stay explicit and must not fabricate output;
- active streams must survive sidebar/inspector layout changes and stable translation callbacks;
- history hydration must not replace in-memory streaming content;
- follow the latest message only while the user is already near the bottom; resizing a column must
  not steal a reader's scroll position;
- layout effects use ResizeObserver/requestAnimationFrame carefully and clean up observers/frames.

Do not buffer an entire unbounded stream merely to render it, and do not silently retry an ambiguous
Provider/Tool operation. UNKNOWN and retry policy remain backend-owned.

## Visual system and layout

- Use the existing CSS variables (`--app-page`, `--app-card`, `--app-sidebar`, `--app-text`,
  `--app-muted`, `--app-line`, `--app-green`) and opaque product surfaces. The immersive public
  entry may keep its intentional particle treatment; authenticated product cards should not acquire
  gratuitous glass/gradient effects.
- Keep global shell styles global and feature geometry in the feature CSS file. Avoid inline style
  systems, CSS-in-JS and page-specific copies of shared tokens.
- Preserve semantic typography tiers, adequate line height and the existing high-information but
  readable spacing. Metadata may be quiet but must remain legible in both themes.
- Three-column workspaces own independent scroll containers. Use `min-width: 0`, `min-height: 0`
  and bounded overflow deliberately so collapse/expand transitions do not hide live content.
- Mobile and narrow layouts must keep all navigation/actions reachable without document-level
  horizontal overflow. Do not solve a desktop problem by making the whole application scroll sideways.
- Motion must be purposeful, interruptible where relevant and disabled or simplified under
  `prefers-reduced-motion`.
- All new visible copy needs Chinese, Japanese and English entries through the existing copy system.
  Do not hardcode one language into an otherwise translated control.

## Current intentional product gaps

Consult the dated integration audit before implementing a gap. Current display-only/planned surfaces
must stay honest until a public contract exists, including attachment/context chips, Organization
sharing/credentials and backend-only management workflows. A prototype control alone is not proof
that the product supports the operation.
