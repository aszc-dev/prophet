#!/usr/bin/env bash
# PARKED (ADR-013): container entrypoint for the future hosted path. The demo/preview
# runs on macOS via `bb serve:mcp` (stdio), not this script.
#
# Build the corpus then serve it. The same pipeline runs locally (bundled fixture)
# and on the host (PROPHET_SOURCE_REPO points at the real source).
set -euo pipefail

SRC="${PROPHET_SOURCE_DIR:-examples/sample-source}"
# Config selects resources/sources/<CFG>.edn kind-rules; default = dir basename.
CFG="${PROPHET_SOURCE_CONFIG:-}"

if [ -n "${PROPHET_SOURCE_REPO:-}" ]; then
  CFG="${PROPHET_SOURCE_CONFIG:-slayer}"
  # clone into a dir named after the config so the provenance repo-name matches
  # (git:<CFG>@... refs and the source's GitHub blob mapping).
  SRC="/data/$CFG"
  if [ -d "$SRC/.git" ]; then
    echo "[entrypoint] pull $PROPHET_SOURCE_REPO"
    git -C "$SRC" pull --ff-only
  else
    echo "[entrypoint] clone $PROPHET_SOURCE_REPO -> $SRC"
    git clone --depth 1 "$PROPHET_SOURCE_REPO" "$SRC"
  fi
fi

echo "[entrypoint] ingest: $SRC (config: ${CFG:-<basename>})"
if [ -n "$CFG" ]; then bb ingest:repo "$SRC" "$CFG"; else bb ingest:repo "$SRC"; fi
echo "[entrypoint] glossary"
bb glossary:build || echo "[entrypoint] glossary:build skipped"

# Wait for the embedder before index:rebuild — TEI downloads the model on first
# boot (minutes). Without this the embed lane would silently go inert (FTS-only).
if [ -n "${SLAYER_EMBED_URL:-}" ]; then
  base="${SLAYER_EMBED_URL%/}"; base="${base%/v1/embeddings}"
  echo "[entrypoint] waiting for embedder at $base/health"
  for _ in $(seq 1 120); do
    curl -sf "$base/health" >/dev/null 2>&1 && { echo "[entrypoint] embedder ready"; break; }
    sleep 5
  done
fi

echo "[entrypoint] index"
bb index:rebuild
if command -v hugo >/dev/null 2>&1; then
  echo "[entrypoint] web"
  bb web:build || echo "[entrypoint] web:build skipped"
fi

# Background freshness loop (ADR-015). Periodically fast-forward the source and
# rebuild the derived index, so whats_new reflects upstream without a redeploy. The
# server opens a fresh DB connection per query (index/query.clj), so the swapped
# kb.db is picked up live. The rebuild targets a temp path and is renamed over kb.db
# (atomic on the same filesystem) — no window where a query sees a half-built index.
# A failed pull or rebuild keeps the previous index. Off unless PROPHET_REFRESH_SECONDS
# is set and a source repo is configured.
if [ -n "${PROPHET_SOURCE_REPO:-}" ] && [ -n "${PROPHET_REFRESH_SECONDS:-}" ]; then
  echo "[entrypoint] freshness loop every ${PROPHET_REFRESH_SECONDS}s"
  (
    while true; do
      sleep "$PROPHET_REFRESH_SECONDS"
      if ! git -C "$SRC" pull --ff-only >/dev/null 2>&1; then
        echo "[refresh] source not fast-forwardable; skip"; continue
      fi
      if bb ingest:repo "$SRC" "$CFG" \
         && bb glossary:build \
         && PROPHET_DB_PATH=kb.db.next bb index:rebuild \
         && mv -f kb.db.next kb.db; then
        bb web:build >/dev/null 2>&1 || true
        echo "[refresh] index refreshed"
      else
        rm -f kb.db.next
        echo "[refresh] rebuild failed; previous index kept"
      fi
    done
  ) &
fi

echo "[entrypoint] serve (MCP-HTTP + static site)"
exec bb serve:mcp-http
