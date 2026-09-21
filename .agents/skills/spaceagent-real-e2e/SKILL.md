---
name: spaceagent-real-e2e
description: Run SpaceAgent backend end-to-end acceptance against real model providers and GitHub MCP in an isolated Trusted Beta environment. Use for staging/live API-key validation, paid model Chat/Embedding flows, GitHub MCP OAuth/import, restart recovery, or release evidence; do not use for ordinary unit tests or the deterministic local fixture suite.
---

# SpaceAgent Real E2E

Use this skill only with explicit authorization to call paid/external APIs and mutate the
named test account. Read `.agent/CURRENT.md` and `docs/operations/PRODUCTION-RUNBOOK.md`
before execution.

## Non-negotiable safety

- Never print, log, commit, paste into tool arguments, or persist raw API keys/passwords.
- `testapikey` must remain Git-ignored and every credential file must be mode `0600`.
- Materialize a temporary secret environment only with
  `scripts/materialize_secret_env.sh`; delete it after the run.
- Use a fresh isolated PostgreSQL database/container and Workspace root. Never run cleanup
  SQL or E2E mutation against production or an unconfirmed existing environment.
- Java remains the only owner of Provider secrets, ModelPool calls, Ledger, Git and business
  state. TypeScript never receives a raw Provider key.
- GitHub must use the platform GitHub MCP OAuth/Connection flow. Never use account password
  as Git/API auth and never store it in a script. Native GitHub routing is absent.
- Stop after one bounded retry. `401` means invalid credential, `402/429` means quota/rate
  blocker, and ambiguous Provider/Tool outcomes remain UNKNOWN rather than retried.

## Workflow

1. Run `scripts/inspect_credentials.sh <credential-directory>` and resolve every reported
   blocker without reading values into conversation output.
2. Read [references/credential-contract.md](references/credential-contract.md), then create a
   mode-0600 temp environment with `scripts/materialize_secret_env.sh`.
3. Read [references/scenarios.md](references/scenarios.md) and declare which phases are
   authorized: Qwen model, optional DeepSeek probe, GitHub MCP, restart, cleanup.
4. Start an isolated current `TRUSTED_BETA` Java/PostgreSQL environment. Qwen is the authoritative
   Chat+Embedding path; DeepSeek is an optional non-blocking `/models` probe unless the user
   confirms available quota.
5. Execute `scripts/run_model_e2e.sh` against the live platform. Preserve only the generated
   redacted evidence JSON and hashes/counts—not model content or credentials.
6. Run `scripts/inspect_github_prerequisites.sh` before GitHub. The platform supports the official
   remote GitHub MCP through discovered OAuth metadata, Spring Security Authorization Code
   + PKCE, official repository tools and a Java-owned checkout lease. Run browser OAuth only
   when the dedicated GitHub App/OAuth App client credentials and exact callback allowlist
   are configured and platform OAuth begin returns an authorization URL. If MFA/CAPTCHA
   appears, pause for the user. The account password must never enter repository files or
   shell history.
7. Restart platform-server and verify login, Conversation, Run/Checkpoint and Ledger evidence
   through the existing restart regression when that phase is in scope.
8. Stop processes/containers, remove temp secret files, and report PASS/FAIL/BLOCKED per
   phase. Update `.agent/CURRENT.md` with redacted evidence and exact blockers.

## Acceptance boundary

Model E2E passes only when real Provider connection test, ModelPool activation, real
Embedding/RAG, Chat, SSE, token usage and secret-redaction assertions pass. GitHub E2E passes
only when MCP OAuth account binding, repository listing, public/private import and managed
Workspace checkout succeed. Missing GitHub MCP server/OAuth capability is `BLOCKED`, not a
model-test failure and not permission to invent a substitute.
