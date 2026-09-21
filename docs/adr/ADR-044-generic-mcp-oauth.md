# ADR-044: Generic MCP OAuth Client Boundary

- Status: Accepted / implemented in M50-PR2B
- Date: 2026-09-05

## Context

M31 implements GitHub's specialized Remote MCP OAuth profile, but other OAuth-protected MCP
servers cannot reuse a GitHub client ID, account model or repository verification flow. M50-PR2A
also requires an authorized Connection to qualify before Tool execution. A generic flow therefore
must discover the resource and authorization server, keep browser state and grants server-side,
and hand the Connection to qualification rather than activating it directly.

The MCP 2025-11-25 Authorization specification uses OAuth Protected Resource Metadata, OAuth
Authorization Server Metadata or OpenID Connect discovery, Authorization Code with PKCE S256 and
the RFC 8707 `resource` parameter. It recommends Client ID Metadata Documents and retains static
pre-registration and Dynamic Client Registration as alternatives. SpaceAgent Trusted Beta does not
yet host a public client metadata document or authorize arbitrary dynamic client creation.

## Decision

Tooling and PostgreSQL remain the sole OAuth state and grant authority. M50-PR2B implements generic
OAuth through operator-pre-registered client profiles:

```text
OAUTH2 Connection/PENDING_AUTH
-> RFC 9728 resource metadata
-> configured authorization-server/client match
-> RFC 8414 or OIDC metadata + PKCE S256 authorization
-> one-use revision-bound callback state
-> encrypted grant + PENDING_VALIDATION
-> M50-PR2A qualification -> ACTIVE
```

Every resource, metadata, authorization and token endpoint must pass the existing public-HTTPS and
reviewed-host policy. Authorization servers and redirect URIs additionally require exact configured
matches. Spring Security OAuth2 Client builds the authorization request, code exchange and refresh;
the official MCP SDK continues to own MCP protocol transport. Authorization and token calls run
outside database transactions.

The OAuth state stores only a SHA-256 state digest and an encrypted bounded transaction containing
PKCE and metadata. It is bound to tenant, user, Connection and Connection revision, consumed once,
then its encrypted transaction is redacted on success, denial or failure. A successful callback
uses revision CAS to store encrypted tokens and invalidates prior capability evidence by moving to
`PENDING_VALIDATION`.

Token refresh does not alter endpoint, account or capability configuration. It therefore uses an
encrypted-grant compare-and-set while retaining the Connection revision and last successful
CapabilitySnapshot. Concurrent refresh losers reload the winner; an expired or invalid winner
requires reauthorization.

GitHub's specialized OAuth/account/repository/checkout API remains separate and retains its own
client configuration. Its callback now receives the same Connection-revision fence and consumed
transaction redaction.

## Consequences

- A configured SpaceAgent deployment can authorize any allowlisted remote MCP server whose OAuth
  authorization server has a matching pre-registered client profile.
- Browser and external compute processes never receive client secrets, access/refresh tokens or
  PKCE verifiers. Public responses contain only state, authorization URL and safe lifecycle data.
- OAuth completion alone never grants Tool execution; qualification remains mandatory.
- V1041 binds historical/new OAuth states to a Connection revision and redacts already-consumed
  provider sessions.
- Dynamic Client Registration, Client ID Metadata Documents, remote token revocation,
  authorization-server selection UI, Registry sync and multi-account Connections remain deferred.
