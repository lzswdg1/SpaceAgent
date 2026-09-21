# SpaceAgent Trusted Beta Release Checklist

## Scope

- [ ] Release is labelled `TRUSTED_BETA`, backend/API-first.
- [ ] Users and repositories are trusted; public untrusted Coding is disabled.
- [ ] Remote automatic merge, frontend parity and deferred blueprint features are not advertised.

## Build and configuration

- [ ] `scripts/release-preflight.sh` passes on a clean commit.
- [ ] Release version and image digest are recorded.
- [ ] All required Secrets are generated, stored outside Git and rotation owners are assigned.
- [ ] `ADMIN_DB_PASSWORD`, `ADMIN_JWT_SECRET`, `ADMIN_LOGIN` and `ADMIN_PASSWORD` are explicit,
      non-development values; `ADMIN_COOKIE_SECURE=true` and Admin API/Web bind only loopback/private addresses.
- [ ] `IDENTITY_ACTIVITY_HASH_KEY` and the dedicated `SYSTEM_ADMIN_JWT_SECRET` are distinct,
  non-development values; generic internal/JWT/Provider/MCP secrets are not reused.
- [ ] System Admin private ingress uses HTTPS with a client certificate and
  `SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL=false`.
- [ ] TLS proxy forwards standard headers; application port is not an unintended public edge.
- [ ] PostgreSQL/Workspace volumes use persistent storage with capacity alerts.
- [ ] A dedicated GitHub App/OAuth App client ID/secret and exact callback allowlist are
      stored outside Git when GitHub Marketplace is enabled; PAT/password fallback is off.

## Verification

- [ ] Reusable CI gates pass Java verify/package, architecture, CLI test/vet, both Web clients,
      Multi-Agent Orchestrator, Python Sandbox Worker, exporter, Gitleaks, Dockerfile and Compose checks.
- [ ] If automatic Chat planning is enabled, the TypeScript HTTP orchestrator is private/reachable,
      plan review pauses before effects, and approval/UNKNOWN resumes only the remaining DAG Steps.
- [ ] TypeScript Multi-Agent tests/typecheck/build pass when that optional service is enabled.
- [ ] `/actuator/health/liveness` and `/actuator/health/readiness` return UP.
- [ ] `/actuator/prometheus` contains JVM/HTTP/Hikari and `spaceagent_agent_*` metrics without
      tenant/user/resource identifiers as labels.
- [ ] OTLP sampling/export is explicitly configured and a platform-server trace appears in Tempo.
- [ ] API golden path passes before and after a platform-server restart.
- [ ] Real staging Provider connection and GitHub MCP OAuth/import are verified without logging Secrets.
- [ ] M29 reviewed SourceMerge returns `remoteUpdated=false` and manual delivery is exercised.
- [ ] Source/dependency and all five image SPDX SBOM artifacts are generated and reviewed.
- [ ] GitHub signing/attestation permissions are configured before claiming a verified supply chain;
      BuildKit provenance alone is not described as release certification.

## Recovery and operations

- [ ] A fresh backup is produced with manifest and SHA-256.
- [ ] Disposable restore verification passes at schema V1098, including Project intake/coding/handoff, Project Plan execution/control, first-chunk evidence, Chat Root Task/TaskPlan scope and Skill Registry,
  User cleanup Job/Step,
      ProjectDirectory/Workspace/Run binding, immutable Project execution context snapshots,
      versioned MCP Marketplace, Registry sync/review and MCP capability/health tables.
- [ ] Restore target confirmation and stop-the-world procedure are rehearsed.
- [ ] UNKNOWN, Provider outage, SourceMerge conflict and Cleanup BLOCKED runbooks are rehearsed.
- [ ] Prometheus, Alertmanager, Grafana, Loki and Tempo are private, healthy and persist to their
      designated operational volumes.
- [ ] A synthetic infrastructure alert and Agent SLO alert reach the designated operator through
      an externally managed Alertmanager receiver; the checked-in no-outbound receiver is not used
      as production notification acceptance.
- [ ] Rollback image and database recovery decision owner are recorded.

## Approval

- [ ] Engineering owner approves test/evidence.
- [ ] Security owner accepts Trusted Beta restrictions.
- [ ] Operations owner accepts backup/restore/on-call readiness.
- [ ] Product owner accepts deferred frontend/automation/public-sandbox scope.
