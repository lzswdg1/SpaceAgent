# Real E2E scenarios

## Phase A: credential preflight

- Files are ignored by Git, mode 0600, non-empty and key/URL-shaped.
- Qwen OpenAI-compatible URL is HTTPS and its host is added to the isolated Provider allowlist.
- No raw value appears in stdout, process arguments, evidence, Git diff or logs.

## Phase B: real Qwen model path

Bounded external calls:

1. Provider create and `/models` connection test.
2. PRIORITY ModelPool create/member/activate/resolve.
3. One Knowledge document process and one retrieval through real Embedding.
4. Agent bound to ModelPool and Knowledge.
5. One normal Chat request and one SSE request.
6. Provider health history, non-zero usage and response secret-redaction checks.

Pass requires all HTTP contracts, non-empty assistant output, positive input/output token
counts, RAG match, selected Provider/Model evidence and no API-key leakage. Keep prompts and
output small to cap cost.

## Phase C: optional DeepSeek probe

Call only the provider `/models`/connection test first. A quota/rate failure is recorded as
`BLOCKED_OPTIONAL` and does not fail Qwen. Do not add DeepSeek to fallback routing unless a
small Chat call succeeds and the user authorized the cost.

## Phase D: GitHub MCP

Current official profile:

- GitHub's official remote MCP is `https://api.githubcopilot.com/mcp/` and expects the MCP
  host to obtain an access token through OAuth 2.1. M31 implements this host flow without
  accepting an operator-supplied PAT for the official Marketplace profile.
- The platform discovers protected-resource and authorization-server metadata, uses Spring
  Security Authorization Code + PKCE, maps `get_me`/`search_repositories`, and derives a
  short-lived Basic checkout grant from the encrypted Tooling OAuth token.

Prerequisites:

- externally reachable HTTPS official Streamable HTTP MCP endpoint;
- official `get_me` and `search_repositories` tools through the M31 profile;
- OAuth begin returns an authorization URL and opaque provider session.

Then verify account binding, repository list, one public URL import, one account-selected
private import and managed Workspace materialization. The checkout grant must be consumed and
redacted. Native Project GitHub and PAT/password fallback are not available. If prerequisites are
absent, stop as `BLOCKED_GITHUB_MCP_ENDPOINT`.
If the official service is selected without a registered client ID/secret/exact callback,
stop as `BLOCKED_GITHUB_MCP_OAUTH_APP_CONFIGURATION`.

## Phase E: restart and cleanup

Restart only after durable state exists. Verify login, Provider, Agent, Knowledge,
Conversation messages, AgentRun, Checkpoint and Ledger evidence. Stop all isolated processes,
remove secret env/state files and leave production untouched.
