# Runtime Feedback Loop

Use this guide only when a task needs a running local service. Source verification does not imply
deployment, and deployment does not require rebuilding the entire stack.

## Activation ladder

Choose the first sufficient level and stop there.

| Level | Use when | Action |
| --- | --- | --- |
| 0. No activation | unit/slice/static proof covers the change | run affected checks only; report that the running stack was not updated |
| 1. Reuse dependencies | a host process or integration test needs PostgreSQL | `docker compose up -d postgres database-init` |
| 2. Host-run service | repeated Java source edits need HTTP/manual smoke | reuse dependencies and run the application from the host using `docs/DEVELOPMENT.md` |
| 3. Recreate, no build | only environment or Compose runtime values changed | `docker compose up -d --no-build --force-recreate <service>` |
| 4. Targeted image rebuild | Dockerfile, packaged source, resources, or image dependencies changed and container proof is required | `docker compose build <service>` then `docker compose up -d --no-deps <service>` |
| 5. Profile/release rebuild | a cross-service image contract or release topology changed | rebuild only affected profiles; full release rehearsal requires explicit scope |

Do not perform more than one activation route merely to duplicate evidence. If host-run HTTP proof
already covers the current artifact, do not also rebuild its container unless container packaging is
part of the changed behavior.

## Cache and state preservation

- Do not run Maven `clean`, remove `target`, prune Docker caches, or force `--no-cache` unless cache
  corruption is evidenced or reproducible clean-build proof is explicitly required.
- Do not run `docker compose down` during the edit-test loop. Preserve PostgreSQL, volumes, OAuth
  state, and healthy unchanged services.
- Do not rebuild `postgres`, `database-init`, Web, Admin, Sandbox, or observability services when
  their Dockerfiles, build inputs, runtime contracts, and configuration are unchanged.
- Inspect `git diff --name-only` before choosing an image target. A Java source change normally
  affects only `platform-server`; an Admin source change normally affects only
  `platform-admin-server`.

## Freshness rules

- Unit and integration tests prove source behavior without updating a running container.
- A smoke test proves the process or container that actually served it. Confirm that the running
  artifact contains the current change before treating the smoke as current evidence.
- Environment-only changes need container recreation but not image rebuilding.
- Dockerfile, copied resource, packaged dependency, or base-image changes need a targeted image
  rebuild when container behavior is in scope.
- A migration can be proved with the owning PostgreSQL integration test. Rebuilding the application
  image is needed only when local runtime activation is separately requested.

## Reporting

State one of:

- `source verified; runtime not activated`;
- `host runtime activated against reused dependencies`;
- `existing image recreated for configuration change`;
- `targeted <service> image rebuilt and activated`;
- `release/profile rebuild explicitly executed`.

Never imply that the local container is current when only source tests ran.
