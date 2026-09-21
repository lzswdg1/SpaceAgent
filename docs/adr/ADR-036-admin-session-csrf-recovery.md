# ADR-036: Recoverable Admin Browser Session with Double-Submit CSRF

- Status: Accepted / implemented in M43-PR1
- Date: 2026-08-27
- Scope: independent Admin browser authentication transport

## Context

The Admin access token and CSRF value were process-memory only while the refresh credential was an
HttpOnly, SameSite=Strict cookie. This prevented credential persistence, but a hard browser refresh
lost the CSRF value and therefore could not call the existing refresh endpoint. A valid durable
Admin Session incorrectly appeared signed out.

Persisting the access or refresh token in Web Storage is unacceptable. Allowing refresh from only a
cookie would remove the explicit CSRF proof. A new endpoint that returns refresh material is also
unnecessary.

## Decision

- Keep the refresh credential in `spaceagent_admin` as a hash and in the browser only as the existing
  HttpOnly, Secure-in-production, SameSite=Strict cookie scoped to `/admin/v1/auth`.
- Issue a second `spaceagent_admin_csrf` cookie containing the random CSRF value. It is deliberately
  readable by same-origin JavaScript, SameSite=Strict, Secure in production and scoped to `/` on the
  dedicated Admin origin.
- Refresh and logout require all three CSRF proofs to agree: the readable cookie, the
  `X-Admin-CSRF` header and the durable Session's CSRF hash. A CSRF cookie alone cannot authenticate
  or mint an access token because the HttpOnly refresh cookie is also mandatory.
- MFA verification and every successful refresh rotate the refresh and CSRF values together.
  Logout expires both cookies. Old refresh/CSRF pairs remain replay-rejected.
- React reads only the CSRF cookie during startup and invokes the existing refresh endpoint once.
  The resulting access token and current CSRF value remain memory-only. No credential is written to
  localStorage, sessionStorage, IndexedDB, URL, log or DOM.
- Missing/expired cookie evidence returns to login. Network failure is shown as a bounded restore
  failure and never fabricates an authenticated session.

## Consequences

- Hard refresh can restore an unexpired Admin Session without re-entering password/TOTP.
- The CSRF value is not treated as a secret or bearer credential. XSS would already execute with
  same-origin authority and remains mitigated by the existing CSP/no-raw-HTML boundary; this design
  addresses cross-site request forgery, not XSS recovery.
- Sessions created before this rollout do not have the readable CSRF cookie and require one fresh
  login. No database migration or copied business state is needed.
- Admin remains independently deployable and uses only `/admin/v1/**`; the browser never calls an
  internal endpoint.

## Rejected

- access/refresh token persistence in Web Storage or IndexedDB;
- refresh authenticated by HttpOnly cookie alone;
- weakening SameSite or Secure production flags;
- returning or exposing the refresh token to JavaScript;
- browser-owned administrator authorization state.
