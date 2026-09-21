#!/usr/bin/env bash
set -euo pipefail

# Exercise the final image as its configured application user. No network, credentials,
# host paths or persistent volumes are available to this disposable smoke container.
image_name="${1:-spaceagent-m10-pr1-platform-server:latest}"
docker run --rm --network none --read-only --tmpfs /tmp:rw,mode=1777 \
  --entrypoint sh "$image_name" -ec '
    test "$(id -u)" -ne 0
    git --version
    test -s /etc/ssl/certs/ca-certificates.crt
    git init --quiet --bare /tmp/spaceagent-git-smoke/origin.git
    git init --quiet --initial-branch=main /tmp/spaceagent-git-smoke/source
    git -C /tmp/spaceagent-git-smoke/source -c user.name=Fixture -c user.email=fixture@example.invalid commit --quiet --allow-empty -m fixture
    git -C /tmp/spaceagent-git-smoke/source push --quiet /tmp/spaceagent-git-smoke/origin.git HEAD:main
    git clone --quiet --mirror /tmp/spaceagent-git-smoke/origin.git /tmp/spaceagent-git-smoke/mirror.git
    git clone --quiet --no-hardlinks --no-checkout /tmp/spaceagent-git-smoke/mirror.git /tmp/spaceagent-git-smoke/workspace
    git -C /tmp/spaceagent-git-smoke/workspace checkout --quiet -b workspace-smoke refs/remotes/origin/main
    test -d /tmp/spaceagent-git-smoke/workspace/.git
    test ! -e /tmp/spaceagent-git-smoke/workspace/.git/objects/info/alternates
    test "$(git -C /tmp/spaceagent-git-smoke/source rev-parse HEAD)" = "$(git -C /tmp/spaceagent-git-smoke/workspace rev-parse HEAD)"
    git --git-dir=/tmp/spaceagent-git-smoke/mirror.git fetch --quiet --prune origin
    printf "%s\n" "PASS: non-root Git clone, mirror, fetch and self-contained workspace in final image"
  '
