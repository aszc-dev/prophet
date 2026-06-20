# syntax=docker/dockerfile:1

# --- deps cache: pre-fetch Clojure runtime deps -----------------------------
FROM clojure:temurin-21-tools-deps-bookworm AS deps
WORKDIR /app
COPY deps.edn ./
RUN clojure -P -M:run

# --- runtime ----------------------------------------------------------------
FROM clojure:temurin-21-tools-deps-bookworm
WORKDIR /app
ARG TARGETARCH
ARG HUGO_VERSION=0.140.2

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates bash \
 && rm -rf /var/lib/apt/lists/*

# babashka (task runner) + hugo extended (web build)
RUN curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash
RUN set -eux; \
    case "$TARGETARCH" in arm64) HA=linux-arm64;; *) HA=linux-amd64;; esac; \
    curl -fsSL "https://github.com/gohugoio/hugo/releases/download/v${HUGO_VERSION}/hugo_extended_${HUGO_VERSION}_${HA}.tar.gz" -o /tmp/hugo.tgz; \
    tar -xzf /tmp/hugo.tgz -C /usr/local/bin hugo; \
    rm /tmp/hugo.tgz; hugo version

COPY --from=deps /root/.m2 /root/.m2
COPY . .

# the bundled fixture needs its own git repo so provenance head-sha resolves
RUN git -C examples/sample-source init -q \
 && git -C examples/sample-source add -A \
 && git -C examples/sample-source -c user.email=ci@prophet -c user.name=prophet commit -qm fixture

ENV MCP_HTTP_HOST=0.0.0.0 \
    MCP_HTTP_PORT=8765 \
    PROPHET_WEB_DIR=/app/web/public \
    JAVA_TOOL_OPTIONS=-Xmx512m

EXPOSE 8765
ENTRYPOINT ["bash", "deploy/entrypoint.sh"]
