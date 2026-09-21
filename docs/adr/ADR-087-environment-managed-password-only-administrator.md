# ADR-087: Environment-managed password-only SystemAdministrator

Status: Accepted; source implemented. Date: 2026-09-15. Explicit user decision: one configured
administrator, account/password login only, no TOTP or additional authentication steps.

## Boundary and rollout

- Admin remains non-tenant, separate database and service. Private ingress, service JWT/mTLS,
  login admission limits, CSRF, HttpOnly refresh cookie, credential versions and audits remain.
- `ADMIN_LOGIN` and `ADMIN_PASSWORD` are authoritative at every startup; the former bootstrap
  login/password names remain fallback aliases. Missing/invalid credentials fail startup, never
  create defaults or disable authentication. Password hashes, not plaintext, are persisted.
- Startup synchronizes the same singleton ID transactionally. Unchanged configuration preserves
  sessions. Credential/name changes increment credential version and revoke old sessions/challenges.
  Every replica must use identical configuration. Restart is required after environment changes.
- Password login directly creates a session. Sensitive commands require an active authorized session,
  existing confirmation/reason/idempotency and audits, but no MFA step-up. This deliberately lowers
  authentication strength; it is not a claim of equivalent MFA security.
- MFA verification, recovery codes, browser password changes and legacy break-glass startup are
  retired. Recovery is changing environment credentials and restarting. No fake MFA timestamps.
- V5 retains historical records, invalidates legacy sessions, adds accurate password-authentication
  timestamps. Applied V1–V4 migrations are immutable. New Admin backend and Admin Web must be deployed
  together; old MFA clients are not compatible. No production operation is authorized by this change.

## Verification checklist

- [x] Startup create/restart/change/rename and invalid configuration, singleton preservation.
- [x] Password-only login, bad password/admission, refresh/CSRF/logout, revoked-session rejection.
- [x] All sensitive command paths use active-session checks; retired MFA paths cannot issue sessions.
- [x] V4 upgrade and fresh V5; no plaintext credentials or fake MFA evidence.
- [x] Admin Web login/recovery and command dialogs; remove TOTP/recovery-code controls.
- [x] Full affected Admin Java/frontend tests, package/build, architecture, Compose and browser checks.

This bounded authentication change does not advance the separate M78 work queue. Runtime activation
and evidence will be recorded at closure; do not infer it from source tests.

## Configuration and evidence

Docker Compose automatically reads the root `.env` and injects the values into the Admin container.
A direct host/JAR launch must export these variables, as with the other application environment
settings. `.env` is not a new plaintext database; only BCrypt hashes are stored in the Admin DB.
Keep the existing strong-password policy: 14+ characters, upper/lower/digit/symbol; no default
password is installed. `ADMIN_DISPLAY_NAME` is optional. JWT/DB/private-link configuration is
infrastructure and remains separate from the two user-entered login fields.

Admin Java14/14 and Shared25/25 plus final Maven package pass, including an intentionally rejected multi-principal V4
upgrade fixture. Admin Web34/34 and build pass. The isolated browser fixture
`WEB_TEST_FIXTURE=admin-password node scripts/verify-web-interactions.mjs` passes36 assertions
across1440px EN/light,1100px ZH/dark,390px JA/light, with fake transport: two-field login,
bad-password rejection/clearing, no OTP/challenge, memory-only tokens and reason-only session
revocation. No live credentials, user changes or production operations were performed.

Local activation is intentionally pending: the existing root `.env` has neither a usable
`ADMIN_LOGIN/ADMIN_PASSWORD` pair nor populated legacy aliases. The operator must supply them
or explicitly authorize generation before a coordinated Admin API/Web restart. The existing
running administrator service was left untouched to avoid an unconfigured restart/lockout.
