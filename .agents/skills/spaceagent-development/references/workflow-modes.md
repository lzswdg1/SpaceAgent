# Workflow Modes

Classify before implementation. When uncertain, choose the higher mode only if a concrete trigger
below applies; uncertainty by itself is not a reason to run the entire repository pipeline.

## FAST

Use FAST for low-risk, locally provable work such as:

- deleting unused private code or an already-retired adapter with no active callers;
- a local null-handling, formatting, validation-message, parsing, or error-mapping fix;
- documentation, Skill, test, non-secret example configuration, or build metadata changes;
- an internal refactor that preserves public contracts and durable state;
- a stable single-module behavior change with a close unit or slice test.

FAST is not allowed when the change touches any of the following:

- an applied or new Flyway migration, persisted-state deletion, or backfill;
- authentication, authorization, tenant boundaries, credential or secret handling;
- durable external effects, `UNKNOWN` reconciliation, Git mutation, or sandbox isolation;
- a public request, response, event, tool, or cross-process contract;
- more than one business owner or a Java/TypeScript/Python ownership boundary.

FAST evidence is the closest meaningful test or structural check plus reference and diff checks.
The absence of a close automated test can justify a small new test; it does not automatically
justify the full suite.

## STANDARD

Use STANDARD when one business owner changes a supported contract but the blast radius remains
bounded, for example:

- adding or changing one endpoint and its application service;
- additive persistence or query behavior without destructive migration;
- updating one adapter plus its owner-facing contract;
- changing one domain lifecycle while retaining the same authority and side-effect model.

Run the affected domain, application, adapter, and controller tests. Add PostgreSQL, HTTP entry-point,
or configuration validation only when that surface changed. A STANDARD change normally remains one
implementation commit and does not create a validation batch.

## STRICT

Use STRICT when one or more of these triggers applies:

- moving business authority or lifecycle ownership;
- destructive schema change, data migration, or compatibility removal involving persisted clients;
- authentication, authorization, tenant isolation, secrets, encryption, or administrator security;
- durable tool, Git, Provider, MCP, payment, or notification effects and their reconciliation;
- cross-runtime or cross-owner contract changes;
- sandbox escape boundaries, production entry points, deployment, release, or external acceptance;
- a migration that must be recoverable across multiple independently deployable phases.

STRICT does not mean maximum ceremony. Create only the phases needed for rollback, recovery, or
independent review. Validate the focused union once and the required release gates once after
production code stabilizes.

## Classifying deletion

- Deleting unreferenced private implementation is FAST.
- Deleting one supported module API with bounded callers is STANDARD.
- Deleting persisted storage, a public or cross-process contract, security behavior, or business
  authority is STRICT.

Even for STRICT deletion:

- do not create a planning-only commit for every substep;
- do not repeat the same status across multiple historical journals;
- do not rerun the full suite after a test-only fixture or documentation correction when production
  code and previously exercised paths are unchanged.
