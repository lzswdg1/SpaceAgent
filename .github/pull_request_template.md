## Summary

Describe the user-visible outcome and why this change is needed.

## Scope and boundaries

- Components changed:
- Public or persisted contracts changed:
- Tenant, authorization, secret, sandbox, or external-effect impact:
- Intentionally excluded work:

## Verification

List the exact focused checks run and their results. If a relevant check was not run, explain why.

## Contributor checklist

- [ ] The change is focused and does not include unrelated generated output or local state.
- [ ] I added or updated deterministic coverage appropriate to the risk.
- [ ] I did not commit credentials, `.env` files, tokens, private data, database dumps, or unredacted logs.
- [ ] I documented the source and license of copied, generated, vendored, or externally derived material.
- [ ] I did not weaken tenant isolation, authorization, audit ledgers, `UNKNOWN` handling, or sandbox boundaries to make a test pass.
- [ ] I updated only the authoritative documentation whose contract actually changed.
- [ ] Every commit includes a DCO sign-off (`Signed-off-by: Name <email>`).

By submitting this pull request, I confirm that I have the right to contribute the material and that
my contribution is provided under the repository's MIT license.
