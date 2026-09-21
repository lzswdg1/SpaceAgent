# Open-Source Readiness

> Status: **HOLD — source and repository identity ready; hosted GitHub settings pending**
> Updated: 2026-09-21

This checklist defines the minimum evidence required before publishing a clean SpaceAgent snapshot.
It does not claim legal review, production certification, or completion of the external acceptance
gates documented elsewhere in the repository.

## Intended public positioning

The first public release should be described as a **self-hosted developer Beta**. It may describe
implemented capabilities and deterministic test evidence, but must not claim enterprise production
readiness, completed penetration testing, guaranteed sandbox isolation, high availability, fixed
support SLAs, or comprehensive compatibility with every model, MCP server, and deployment profile.

## Release blockers

All items in this section are required before publication.

- [x] Exclude the private, provenance-unverified `copy/browser` reference snapshot from the public
  exporter and distributed artifacts. It is not present in the reviewed public candidate.
- [x] Record the project-owner origin and MIT redistribution permission for the SpaceAgent logo,
  favicon and retained UI/interaction prototypes in `ASSET-PROVENANCE.md`. The prototypes contain
  no bundled font or remote CDN asset.
- [x] Scan the exact history-free candidate for credentials, tokens, private keys, personal data,
  private repository content, database dumps and production logs. The candidate contains no Git
  history and the 2026-09-21 scan found no confirmed live credential. The private development
  history is deliberately not published. Any credential disclosed outside Git must still be
  revoked before publication.
- [x] Release method selected: create a clean new repository containing a reviewed snapshot of the
  selected current commit, not the existing repository's refs or object history. The snapshot will
  start a new `main` history with one reviewed initial commit; local `.gitignore` rules are not
  treated as history sanitation.
- [ ] Enable GitHub private vulnerability reporting and verify the Security-tab workflow. Update
  `.github/ISSUE_TEMPLATE/config.yml` if the final repository owner or name changes.
- [ ] Enable and test the shared private GitHub intake described by `SECURITY.md` and
  `CODE_OF_CONDUCT.md`; conduct reports must use the `[CODE OF CONDUCT]` title prefix.
- [x] Configure release CI to generate source/dependency and per-image SPDX SBOM artifacts. A release
  owner must still review each generated inventory and required notice before publication.
- [ ] Complete a clean-clone build and documented Docker deployment on a machine without local image
  or dependency caches. Verify upgrade and rollback instructions without using private credentials.
- [x] Confirm example configuration contains placeholders only. Trusted Beta preflight and Java
  startup validation reject development Admin/database/JWT values, insecure Admin cookies and
  public Admin ingress; metrics and Docker-owning profiles remain loopback/private and opt-in.
- [ ] Review repository visibility, default branch, branch protection, required checks, CODEOWNERS,
  release signing, and maintainer permissions.
- [x] Use the confirmed `https://github.com/lzswdg1/SpaceAgent` identity for the Go module/imports,
  GoReleaser linker path and `CONTRIBUTING.md` clone URL. See
  `docs/operations/NEW-REPOSITORY-SETUP.md`.

## Licensing boundary

- Repository-owned source is offered under the root MIT license unless a file says otherwise.
- Contributions use DCO sign-off and are accepted under the same MIT license (`inbound = outbound`).
- Apache Maven and Maven Wrapper retain Apache License 2.0 attribution and are not relicensed by the
  root MIT license.
- Package-manager dependencies, base images, and independent Compose images retain their upstream
  licenses and notice obligations.
- No source, asset, fixture, generated output, or copied directory may enter the public snapshot
  without documented provenance and redistribution rights.
- `THIRD_PARTY_NOTICES.md` is a boundary summary, not an exhaustive legal inventory.

No legal review has been completed or is implied by these files. Obtain qualified advice if the
release owner needs a legal determination.

## Clean public snapshot procedure

1. Freeze the candidate commit and record its SHA.
2. Export or clone into a new empty directory; do not copy the developer working tree wholesale.
3. Include only reviewed tracked files and explicitly exclude local `.env`, `.run`, `output/`, IDE,
   build, backup, test-secret, database, and credential material.
4. Verify that the private `copy/browser` path is absent from the exported directory.
5. Run secret, history, dependency, license, and container scans against the exact candidate.
6. Build and test from the clean directory using documented commands and placeholder credentials.
7. Review the final archive and image layers, not only the Git tree.
8. Publish a signed tag with release notes listing Beta limitations and remaining external gates.

## Community baseline

The repository should publish and link:

- `LICENSE`
- `SECURITY.md`
- `CODE_OF_CONDUCT.md`
- `CONTRIBUTING.md`
- `THIRD_PARTY_NOTICES.md`
- issue forms and the pull request template under `.github/`

Issue and pull request forms must continue to prohibit secrets and private data. Security reports
must use a private channel. Contributor commits require a Developer Certificate of Origin sign-off;
accepted contributions are MIT-licensed without a separate CLA unless maintainers announce a future
process change before accepting the contribution.

## Evidence record

For the first public Beta, attach or link the following evidence to the release decision:

| Evidence | Required result |
| --- | --- |
| Source and Git-history secret scan | No unresolved high-confidence secret; all discovered real credentials revoked |
| Provenance review | No unlicensed or authorization-unknown source or asset in snapshot |
| Dependency and image review | SBOM and required notices retained with release artifacts |
| Clean-clone build | Java, Web, Admin Web, CLI, orchestrator, and worker scopes build as documented |
| Docker smoke | Fresh install, health checks, restart, upgrade, backup, and rollback paths documented |
| Security channel | Private report successfully reaches the maintainers |
| Community files | Links and templates render correctly in the final public repository |

An unchecked item is a release risk, not evidence that the corresponding control exists elsewhere.
