# Frontend quality, performance and security gates

Read this reference for frontend reviews, performance work, security-sensitive integration,
accessibility changes and release readiness.

## Review boundary

Review the complete path that the changed browser surface actually uses:

```text
visible control -> component state -> feature API/SSE client -> authenticated transport
-> public Java HTTP contract -> authoritative backend evidence -> response reconciliation
```

Classify findings separately:

- browser rendering/state defect;
- public contract mismatch;
- backend authorization/domain defect;
- external or environment blocker;
- intentional display-only/planned capability.

Do not “fix” a backend authority problem with frontend checks, or label a missing external credential
as a React defect.

## Security checklist

- No credential, Provider/MCP auth material, prompt, Tool argument/result, checkout grant, internal
  token, host path or private endpoint in DOM, storage, URL, console, analytics or error copy.
- No access/refresh token persistence in Web Storage. Preserve the HttpOnly refresh-cookie and
  in-memory access-token model.
- All business reads/mutations are same-origin public APIs. Browser-supplied tenant/owner/role status
  is presentation only; the backend reauthorizes every operation.
- Dynamic route segments are encoded. Arbitrary external URL/path/header input is not forwarded
  unless the public backend contract validates and owns it.
- Model/user/Tool text is rendered as escaped text. Introducing Markdown/HTML requires an explicitly
  reviewed parser, sanitizer, URL protocol policy and CSP-compatible rendering; never use raw
  `dangerouslySetInnerHTML` for Provider output.
- Preserve production CSP/HSTS/frame/referrer/permissions/COOP headers in `apps/web/nginx.conf`.
  Avoid eval, inline script dependencies, unbounded `data:` use or new cross-origin `connect-src`
  without a documented security decision.
- Bound file size/type, text length, list size, error preview and stream accumulation in both UI and
  backend. Frontend limits improve UX but never replace backend enforcement.
- Destructive actions, Organization changes and real external/paid diagnostics require explicit
  user intent and visible terminal evidence.
- Never auto-retry an ambiguous Provider, MCP, Tool, Git or Runtime effect. Show UNKNOWN safely and
  defer reconciliation to the owning backend workflow.

## Performance checklist

- Preserve lazy authenticated page chunks. Compare `npm run build` output with the previous build
  when adding a dependency or materially changing a route; explain meaningful initial/chunk growth.
- Avoid dependencies for functionality already covered by React/browser APIs. Check tree-shaking and
  route placement before adding a package.
- Parallelize independent requests, eliminate per-row request fan-out and use backend pagination.
- Stabilize callbacks/derived inputs that control effects or history hydration. Memoize when it
  prevents demonstrated reload/render work, not by default everywhere.
- Cancel or ignore stale requests. Guard async completion by current selection/tenant and prevent
  duplicate mutations.
- For streaming, append only the affected message, keep payloads bounded and avoid reparsing all
  historical content per delta. Preserve layout continuity during sidebar and inspector transitions.
- Canvas, scroll and resize handlers use passive events where possible, requestAnimationFrame for
  paint work, observer cleanup and reduced-motion behavior. They must stop off-screen/unmounted work.
- Use CSS containment/overflow intentionally on long workspaces. Avoid global layout reads followed
  by writes in tight loops.

## Accessibility and interaction checklist

- Use semantic button/link/form/dialog elements; every input has a programmatic label and every icon-
  only action has an accessible name.
- Keyboard users can reach, operate and dismiss every action. Enter/Shift+Enter behavior must match
  the composer contract without trapping focus.
- Focus evidence is visible and belongs to the component border/element, without accidental double
  outlines or focus removal.
- Loading, error, live and terminal state use appropriate `aria-live`, `role=alert`, disabled and
  busy semantics. Color is not the only state signal.
- Modal focus/escape behavior and background interaction must be checked when dialogs change.
- Validate both themes, relevant languages, text growth, desktop and 390x844-class mobile widths.
  Check independent scroll regions and absence of document-level horizontal overflow.
- Honor `prefers-reduced-motion`; animations must not be required to understand state.

## Deterministic validation

From the repository root:

```bash
# affected pure/API/SSE regression during iteration
npm run test --workspace @spaceagent/web -- <affected-test-file>

# frontend completion gates
npm test
npm run build
scripts/check-architecture.sh
git diff --check
```

The Vitest environment is Node. Prefer pure state/format/path/stream tests and injected fetchers.
Do not add a heavyweight browser test framework for one regression without a demonstrated need.

When public Java contracts change, use `spaceagent-verification` to select focused HTTP/Application
tests plus the proportional broader gate. For visual/interaction changes, run browser QA against the
local same-origin app and record themes, languages, viewport sizes, keyboard/focus/scroll behavior
and console/network diagnostics actually checked.

Deterministic validation must not invoke a paid model, real Embedding, public MCP/GitHub OAuth,
SearXNG or user credential. Those remain `LIVE NOT RUN` unless the user explicitly authorizes
`spaceagent-real-e2e`.
