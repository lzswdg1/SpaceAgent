# SpaceAgent Independent Admin Web Plan

## 2026-09-15 password-only configured administrator

Current authentication follows ADR-087, superseding the historical MFA workflow below. Admin login
has only login name/password and stores the returned access session in memory; refresh cookie/CSRF
recovery is unchanged. The old MFA challenge response is rejected as incompatible, not mistaken for
an authenticated session. All four management command pages remove TOTP inputs/reauthentication.
Singleton security retains audited session revocation and explains environment-managed credentials;
recovery-code and forced browser-password-change UI/API methods are removed.

Admin Web34/34/build, Admin Java14/14 + Shared25/25/package, architecture and Compose validation pass.
Real-component fake-transport browser checks pass36 assertions across EN/light, ZH/dark and JA/mobile;
no real administrator credentials or business operations were used. No dependency added; Admin JS
96.21KB gzip. Source complete, coordinated API/Web activation pending actual .env credentials.

> Started: 2026-08-26
> Milestone: M41-PR1 through M49-PR1, plus M63 governance evidence integration
> Status: COMPLETE
> Backend authority: `apps/platform-admin-server` + owner APIs in `apps/platform-server`

## Boundary

`apps/admin-web` is a separately built and deployed React/TypeScript client. It calls only
same-origin `/admin/v1/**`. It owns rendering, transient forms, navigation and theme/language
preferences. Admin identity/session/command/audit remain in `spaceagent_admin`; all User,
Organization, credential, Runtime and cleanup facts remain in `spaceagent_platform` owner modules.

The access token is process-memory only. The refresh credential is an HttpOnly, SameSite=Strict
cookie. M43-PR1 adds a separate readable SameSite=Strict CSRF cookie so a hard reload can submit the
required double-submit header and rotate the durable Session. The CSRF value is not a bearer
credential and cannot restore without the HttpOnly cookie. No access/refresh token enters Web
Storage, and no tenant token, Provider/MCP credential, prompt/content payload, internal endpoint,
database connection or impersonation capability enters the client.

## Implemented surfaces

- password challenge + TOTP Admin login, refresh rotation and logout;
- hard-refresh Session recovery through HttpOnly Refresh + double-submit CSRF cookies;
- Dashboard freshness and owner-module resource/risk projections;
- read-only AgentVersion review and scheduled-action governance counts through a dedicated
  least-privilege internal scope and audited Admin API; prompts, version bodies and actor details
  remain outside the administration client;
- bounded global User search/detail and create/suspend/restore/preflight/delete commands;
- one-time activation reference and durable User cleanup Job/Step evidence;
- bounded Organization index and redacted Provider/Agent-key/MCP inventory;
- recent-MFA Organization create/update/ADR-025 durable deletion commands;
- bounded Organization member search/filter plus add/reactivate, role update, remove and explicit
  OWNER transfer commands;
- bounded per-User redacted Provider and Agent identity/status pages;
- command lookup/UNKNOWN reconciliation and append-only Audit pagination;
- global User/Organization Cleanup queue, blocker aggregation, ordered Step detail and paginated
  recent Command/UNKNOWN/FAILED queues without blind retry;
- per-User 12-kind redacted resource count/drill-down with lazy selected-kind pagination and no
  prompt/content/path/Tool payload/credential exposure;
- singleton SystemAdministrator identity/session page, current-password + recent-TOTP recovery-code
  rotation, response-only eight-code display, recovery-code MFA and mandatory password-change gate;
- official MCP Registry synchronization jobs and candidate review queue with bounded detail,
  recent-MFA reasoned approval/rejection, and no automatic installation or execution;
- independent Nginx/Docker/Compose Admin profile with CSP/HSTS/no-store headers;
- optional, explicitly configured links between tenant and Admin planes.

## Acceptance

- typed route/payload/idempotency and single-flight refresh tests;
- nullable audit and safe blocker/cleanup view-model tests;
- Admin and tenant Web production builds;
- Admin Docker image and default/all-profile/release Compose validation;
- desktop/mobile, light/dark, Chinese/Japanese/English, keyboard focus and console diagnostics;
- no real administrator credential, Provider, GitHub, MCP or production database in automation.

M42-PR2 additionally passed local PostgreSQL-backed acceptance: platform/admin/Web readiness,
Password+TOTP session creation and audited Dashboard/User/Provider/Agent reads. The local bootstrap
was disabled immediately after the first administrator was durably created.

The M63 governance evidence integration extends the existing overview without adding a second
business authority or changing navigation. `admin-web` calls only
`GET /admin/v1/agent-version-governance/evidence`; `platform-admin-server` records the sensitive
read and issues a short-lived service token restricted to
`system-admin:agent-version-governance:read`; `platform-server` returns bounded aggregate counts.
The panel loads independently from the cached dashboard so one failed projection does not disguise
the health of the other.

## M49-PR1 singleton alignment

M49 removes every M47 multi-principal create/suspend/restore/credential-recovery control and typed
client method after Admin V4 made those compatibility routes fail closed. The page now renders the
authenticated singleton directly, lists only its Sessions, protects the current Session and permits
revoking another Session. Recovery-code rotation calls
`POST /admin/v1/auth/recovery-codes/rotation` only after explicit current-password and recent-TOTP
proof. Returned codes remain component memory and are discarded on dismissal, navigation or reload.

The local production image was rebuilt without restarting Java or PostgreSQL. Its healthcheck uses
IPv4 loopback explicitly because Alpine resolves `localhost` to an unbound IPv6 address in this
image. Authenticated destructive/security acceptance remains live-not-run without current operator
credentials.
