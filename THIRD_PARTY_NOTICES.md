# Third-Party Notices

This document records licensing boundaries for the SpaceAgent developer Beta. It is a release aid,
not a complete dependency inventory, legal opinion, or substitute for the license text shipped by
an upstream project.

## SpaceAgent-owned source

Unless a file states otherwise, source authored for this repository is available under the root
[MIT License](LICENSE). The MIT license does not relicense third-party packages, container images,
tools, fonts, artwork, copied code, or user-provided content.

Contributions accepted under the process in [CONTRIBUTING.md](CONTRIBUTING.md) are licensed to the
project under the same MIT terms.

## Repository-owned brand and prototype assets

The SpaceAgent logo, its prototype-local copy, the favicon derivative and the UI/interaction
prototypes under `apps/web/prototypes/**` were created by the project owner, including AI-assisted
generation of the brand image. The project owner authorizes those assets for use, modification and
distribution with SpaceAgent under the root MIT License. Exact provenance and the third-party
boundary are recorded in [ASSET-PROVENANCE.md](ASSET-PROVENANCE.md).

The prototypes bundle no font files or remote CDN assets. System font names and third-party product
or trademark references remain the property of their respective owners and are not relicensed or
claimed by SpaceAgent.

## Apache Maven and Maven Wrapper

The Unix `mvnw` launcher is adapted from the
[Apache Maven Wrapper](https://github.com/apache/maven-wrapper). The wrapper metadata downloads
Apache Maven and, when used by compatible tooling, the Maven Wrapper artifact from the Apache Maven
repositories. Apache Maven and Maven Wrapper are Apache Software Foundation projects distributed
under the Apache License 2.0; they are not relicensed under SpaceAgent's MIT license.

A copy of the Apache License 2.0 is retained at
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

No Maven Wrapper JAR is currently tracked in this repository. Release packaging must preserve any
upstream notices required by wrapper or Maven artifacts that it redistributes.

## Contributor Covenant

`CODE_OF_CONDUCT.md` is adapted from Contributor Covenant version 2.1 and retains its required
attribution and source links. Contributor Covenant is distributed under the
[Creative Commons Attribution 4.0 International License](https://creativecommons.org/licenses/by/4.0/)
and is not relicensed by SpaceAgent's MIT license.

## Package-manager dependencies

Java/Maven, JavaScript/npm, Go, and Python dependencies are declared in their respective manifests
and lock files. Each dependency remains subject to its own license and notice requirements. A
consumer or distributor must inspect the resolved dependency graph for the exact release rather
than treating this file as an exhaustive software bill of materials.

Some dependency metadata currently names licenses including MIT, ISC, BSD, Apache-2.0, MPL-2.0,
CC-BY-4.0, and PostgreSQL-family terms. This summary does not resolve dual licenses, transitive
artifacts, optional dependencies, generated assets, or license exceptions.

## Independent container images

The Compose files can download or reference independent images, including:

- `pgvector/pgvector`
- `milvusdb/milvus`
- `apache/tika`
- `nginx`
- Prometheus and Alertmanager
- Grafana, Loki, Tempo, and Alloy
- `busybox`

Those images are separate distributions under their upstream projects' licenses and may contain
additional operating-system packages. Merely referencing or orchestrating an image does not make it
MIT-licensed. Image publishers and operators remain responsible for reviewing the exact image
digest, bundled notices, source-offer obligations, trademark rules, and redistribution terms.

Images built from SpaceAgent Dockerfiles can also incorporate third-party base images and packages;
their release artifacts require the same review.

## Private reference excluded from public distribution: `copy/browser`

The private development repository contains a `copy/browser` reference tree without a license,
upstream reference, or other record sufficient to verify its origin and redistribution
authorization. Its package name does not establish ownership.

**That tree is not cleared for external distribution.** It remains isolated from active builds and
the public exporter excludes `copy/`. It must remain absent from public snapshots and distributed
artifacts unless maintainers later either:

1. establish documented provenance and compatible redistribution terms, preserving required
   notices; or
2. replace it with independently authored, reviewed source under documented terms.

This notice does not grant permission to use or distribute that code.

## Release responsibility

Before each public release, generate a dependency and image inventory, scan for untracked vendored
code or assets, collect required notices, and review changes since the prior release. The initial
open-source readiness checklist is maintained in
[docs/operations/OPEN-SOURCE-READINESS.md](docs/operations/OPEN-SOURCE-READINESS.md).

No legal review is claimed to have been completed.
