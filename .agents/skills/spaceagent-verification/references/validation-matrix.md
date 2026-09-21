# Validation Matrix

Select a workflow level first, then include only the surfaces changed by the diff.

| Level | Typical scope | Minimum evidence | Broad gates |
| --- | --- | --- | --- |
| FAST | local fix, private deletion, docs, Skill, test, example config | closest test or structural check, reference scan, `git diff --check` | none by default |
| STANDARD | one owner module, bounded public API or additive persistence | affected domain/application/adapter/controller tests | only the affected production surface |
| STRICT | authority, security, destructive persistence, durable effects, cross-runtime, release | focused union after code stabilizes | one full closure pass when required |

## Surface routing

| Changed surface | FAST | STANDARD | STRICT or release closure |
| --- | --- | --- | --- |
| Java domain/application | closest test class | affected module or feature tests | focused union, then full Maven once if closure needs it |
| SQL/persistence | mapping/static check when no runtime behavior changed | owning PostgreSQL fresh/upgrade or integration tests | full affected migration matrix once |
| HTTP/security/serialization | owning controller or application test | production Spring route and affected security chain | affected entry-point suite plus release smoke |
| TypeScript/Python/Go | changed-package syntax or focused test | changed runtime suite | affected runtime full suite once |
| Build/configuration | parser or targeted config check | affected package/profile/startup check | release package and profile matrix once |
| Compose | no check unless file/profile changed | validate affected profile | validate all release profiles once |
| Architecture/dependencies | reference or import scan | affected architecture rule | full architecture gate once |
| Documentation/Skill | link/frontmatter/Skill validation and diff check | same, plus public-contract consistency if applicable | no product regression solely for docs |

## Specialized minimum coverage

Apply these rows only when the named behavior changes:

- Tenant, authentication, authorization, administrator, or credential changes: positive and negative
  access paths, cross-tenant denial, secret non-disclosure, and relevant audit evidence.
- Durable Tool, Provider, MCP, Git, or notification effects: idempotency, retry ownership,
  `UNKNOWN` handling, reconciliation, and invocation or effect ledger evidence.
- TaskPlan, Run, lease, or orchestration changes: snapshot immutability, lease fencing, restart or
  recovery semantics, and Java-authoritative state.
- Workspace or sandbox changes: path and tenant isolation, command policy, timeout or cancellation,
  compare-and-set behavior, and supported production executor integration.
- Persistence migrations: fresh schema, supported upgrade path, constraints and indexes, and
  compatibility or cleanup behavior.
- Streaming or telemetry changes: ordering, reconnect or replay boundary, correlation identifiers,
  redaction, and degraded collector behavior.

These are coverage requirements, not commands to run every specialized suite for unrelated work.

## Rerun decision

After a failure, classify the repair:

- Production code, migration, dependency, runtime wiring, or shared test setup changed: rerun every
  affected broader gate whose prior evidence is now stale.
- Fixture or assertion only changed, and production code plus the exercised production path are
  unchanged: rerun the failed set and any directly dependent focused set.
- Documentation or status only changed: retain product-test evidence.

## Release evidence placeholders

When STRICT release closure is actually requested, record concrete values instead of prose:

- Git commit and dirty-worktree state;
- Java and non-Java suite outcomes;
- package, architecture, migration, security, and affected Compose profile outcomes;
- production entry-point or deterministic smoke result;
- external acceptance status, explicitly separated from deterministic validation;
- intentionally unrun gates and the reason.

Do not infer exact aggregate test totals from stale reports. Exact counts are optional outside a
release evidence record.
