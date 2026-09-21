FROM node:22-alpine

LABEL org.opencontainers.image.licenses="MIT"

RUN apk add --no-cache \
       ca-certificates git go maven openjdk21-jdk python3 \
    && corepack enable \
    && corepack prepare pnpm@10.15.0 --activate

RUN python3 -m venv /opt/spaceagent-sandbox-venv
ENV PATH="/opt/spaceagent-sandbox-venv/bin:/usr/local/bin:/usr/bin:/bin"

WORKDIR /opt/spaceagent-sandbox-worker
COPY LICENSE /licenses/SpaceAgent-MIT.txt
COPY THIRD_PARTY_NOTICES.md /licenses/THIRD_PARTY_NOTICES.md
COPY ASSET-PROVENANCE.md /licenses/ASSET-PROVENANCE.md
COPY licenses /licenses
COPY workers/sandbox-worker/pyproject.toml ./
COPY workers/sandbox-worker/sandbox_worker ./sandbox_worker
RUN --mount=type=cache,target=/root/.cache/pip pip install .

LABEL com.spaceagent.image="sandbox-toolchain-v1"

EXPOSE 9200
ENTRYPOINT ["python", "-m", "sandbox_worker", "--host", "0.0.0.0", "--port", "9200"]
