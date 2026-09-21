FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
COPY apps/admin-web/package.json apps/admin-web/package.json
# npm can omit platform packages from a lockfile generated on another OS.
# Install the Linux binaries explicitly so the Alpine production build is reproducible.
RUN npm ci --ignore-scripts \
    && arch="$(uname -m)" \
    && if [ "$arch" = "aarch64" ]; then \
         npm install --no-save --ignore-scripts \
           @rollup/rollup-linux-arm64-musl@4.62.4 @esbuild/linux-arm64@0.25.12; \
       else \
         npm install --no-save --ignore-scripts \
           @rollup/rollup-linux-x64-musl@4.62.4 @esbuild/linux-x64@0.25.12; \
       fi

COPY apps/web apps/web
ARG VITE_ADMIN_WEB_URL=""
ENV VITE_ADMIN_WEB_URL=$VITE_ADMIN_WEB_URL
RUN npm run build

FROM nginx:1.31-alpine
LABEL org.opencontainers.image.licenses="MIT"
COPY LICENSE /licenses/SpaceAgent-MIT.txt
COPY THIRD_PARTY_NOTICES.md /licenses/THIRD_PARTY_NOTICES.md
COPY ASSET-PROVENANCE.md /licenses/ASSET-PROVENANCE.md
COPY licenses /licenses
COPY apps/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/apps/web/dist /usr/share/nginx/html
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
