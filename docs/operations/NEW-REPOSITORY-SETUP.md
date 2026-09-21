# Public Repository Setup

The public repository identity is now fixed:

- Repository: `https://github.com/lzswdg1/SpaceAgent`
- Clone URL: `https://github.com/lzswdg1/SpaceAgent.git`
- CLI module: `github.com/lzswdg1/SpaceAgent/cli`

## Completed source-identity migration

The Go module, all CLI imports, GoReleaser linker paths and `CONTRIBUTING.md` clone command use the
final identity above. The previous Owner/module string and temporary repository-identity blocker are
no longer present in distributable source.

## Hosted GitHub settings

After the repository exists and before it becomes public, configure and test:

- repository visibility, default branch and maintainer/team permissions;
- CODEOWNERS using real repository teams or usernames;
- private vulnerability reporting and the Security-tab intake;
- branch protection and the exact required CI/CodeQL checks;
- DCO enforcement;
- Dependabot access and update policy (`.github/dependabot.yml` is already present);
- release environments, package permissions and signing/attestation identity;
- signed tags/releases if required by the project owner.

Do not add fake CODEOWNERS entries or a temporary security email. BuildKit SBOM/provenance output is
not, by itself, a claim that release signing or GitHub attestations have been configured.

## Initialization sequence

After the final clean-directory audit passes:

```text
git init -b main
git add .
git status --short --ignored
git commit -s -m "chore: publish initial SpaceAgent source snapshot"
```

Review the staged file list before the first commit. `PUBLIC-SOURCE-MANIFEST.txt` is intended to be
tracked; `.git`, credentials, caches, build outputs and private reference material must remain absent.
Remote creation and push are separately authorized actions.
