#!/usr/bin/env bash
# Build the corpus then serve it. The same pipeline runs locally (bundled fixture)
# and on the host (PROPHET_SOURCE_REPO points at the real source).
set -euo pipefail

SRC="${PROPHET_SOURCE_DIR:-examples/sample-source}"

if [ -n "${PROPHET_SOURCE_REPO:-}" ]; then
  SRC=/data/source
  if [ -d "$SRC/.git" ]; then
    echo "[entrypoint] pull $PROPHET_SOURCE_REPO"
    git -C "$SRC" pull --ff-only
  else
    echo "[entrypoint] clone $PROPHET_SOURCE_REPO"
    git clone --depth 1 "$PROPHET_SOURCE_REPO" "$SRC"
  fi
fi

echo "[entrypoint] ingest: $SRC"
bb ingest:repo "$SRC"
echo "[entrypoint] glossary"
bb glossary:build || echo "[entrypoint] glossary:build skipped"
echo "[entrypoint] index"
bb index:rebuild
if command -v hugo >/dev/null 2>&1; then
  echo "[entrypoint] web"
  bb web:build || echo "[entrypoint] web:build skipped"
fi

echo "[entrypoint] serve (MCP-HTTP + static site)"
exec bb serve:mcp-http
