# Security Policy

SpaceAgent is currently distributed as a **developer Beta**. Security reports are welcome, but
the project does not yet promise a production support SLA or compatibility window.

## Supported versions

Security fixes are made on a best-effort basis for the latest published Beta release. Development
branches, older snapshots, local modifications, and unsupported deployment combinations are not
covered by a security-maintenance commitment.

## Reporting a vulnerability

Do **not** open a public issue, discussion, or pull request for a suspected vulnerability. Public
reports can expose other installations before a fix is available.

Use GitHub's private vulnerability reporting flow:

1. Open this repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Include the affected revision or release, deployment mode, impact, reproduction steps, and a
   minimal proof of concept.

Before the repository is made public, maintainers must enable private vulnerability reporting. If
that feature is unavailable, the release owner must replace this paragraph with a monitored private
contact. Do not publish a real person's private address in this file without their consent.

Please redact API keys, access tokens, cookies, personal data, private repository contents, database
dumps, and production logs. If a secret was exposed while investigating, revoke it before sending
the report and describe only the credential type and scope.

## What to expect

Maintainers will make a best-effort attempt to acknowledge a complete report, validate it, coordinate
a fix, and credit the reporter if requested. Response and remediation times depend on severity and
maintainer availability; no fixed timeline is promised during Beta.

Please allow maintainers a reasonable opportunity to investigate and release a fix before public
disclosure. This policy does not authorize accessing data or systems you do not own or have explicit
permission to test.

## Security boundaries

Reports are especially useful when they involve tenant isolation, authentication or administrator
access, credential handling, tool or Git side effects, sandbox escape, MCP or Provider integrations,
RAG document authorization, or durable recovery state. Deployment hardening and external acceptance
gaps documented in the repository are not claims of completed production certification.

## Docker API and observability boundary

The optional Sandbox Worker holds a writable Docker Socket and must be treated as host-root-equivalent
infrastructure if compromised. Its Compose profile is default-off, has no published port and is
supported only for trusted code in controlled environments. Public-untrusted code execution,
production gVisor acceptance and strong sandbox-escape resistance are not certified. Prefer a
dedicated execution node, rootless Docker or an authorization-enforcing Socket proxy.

Alloy mounts the Docker Socket with `:ro` for discovery and log collection. That flag does not make
the Docker API read-only; a compromised collector can still issue daemon requests permitted by the
socket. Keep the observability profile private and minimize daemon access.

`/actuator/prometheus` is anonymous for private scraping. The platform application port must remain
loopback/private or place that endpoint behind a separately authenticated network boundary before
expanding the listen address.
