# Quickstart

Get Prophet running on a clean clone, with no embedder configured (the default —
search runs on FTS + exact-alias + graph; the vector lane stays inert). Enabling
hybrid retrieval is one section down.

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| JDK | 21 (8+ works) | runs the index/MCP layer; Clojure 1.12.0 (`deps.edn`) targets the JVM |
| Clojure CLI | 1.12+ | `clojure -M:run …` entrypoint |
| Babashka (`bb`) | 1.x | task runner (`bb.edn`) |
| Hugo (extended) | recent | only for `bb web:build` |
| Docker | any | only to run a TEI embedder for hybrid mode (optional) |
| pre-commit | 4.x | optional, for the commit hooks |

**Native-lib caveat (`vec0`).** The sqlite-vec loadable extension is vendored for
**macOS arm64 only** (`resources/native/macos-arm64/`). The index layer therefore
runs today on Apple Silicon; the linux-x86_64 `vec0.so` lands with the deployment
work (going-public plan §4.1).

## Runtime split (ADR-006)

sqlite-vec loads only under a full JVM, never under Babashka. So every
index-touching `bb` task shells out to `clojure -M:run <cmd>`. Pure transforms
(`extract/*`, `resolve/*`, adapters) are runtime-agnostic.

## Smallest end-to-end loop (no embedder)

```sh
# optional: install commit hooks
pre-commit install

# 1. derive the index from the bundled note store (kb/)
bb index:rebuild

# 2. search — ranked nodes, each with a provenance ref
bb search "held-out"

# corpus summary (machine-readable EDN)
bb stats
```

`bb search` returns nodes whose observations carry `git:<repo>@<sha>:<path>`
provenance refs. No network, no GPU, no model required.

## Ingest a source

`bb ingest:repo` turns a source repo into nodes under `kb/`. A tiny throwaway
source ships at `examples/sample-source/` (a card, a config, a content page):

```sh
bb ingest:repo examples/sample-source   # creates 3 demo nodes in kb/
bb index:rebuild
bb search "DemoEval"

# the demo nodes are not part of the canonical corpus — revert them:
git checkout -- kb/ && git clean -fdq kb/
```

Ingest is deterministic: a second run with no source change is a no-op (stable
ULIDs via `source_key`, content-hash skip).

## Enabling hybrid retrieval (optional)

Point the embed client at any OpenAI-compatible `/v1/embeddings` endpoint, then
rebuild — `bb index:rebuild` embeds the corpus and search switches to `:hybrid`.

```sh
export SLAYER_EMBED_URL=http://127.0.0.1:8080      # the endpoint
export SLAYER_EMBED_MODEL=Qwen/Qwen3-Embedding-0.6B # 1024-dim
export SLAYER_EMBED_API_KEY=...                     # only if the server requires it
bb index:rebuild
```

- **Use `127.0.0.1`, not `localhost`.** The JVM HttpClient resolves `localhost`
  to IPv6 `::1`; many local model servers bind IPv4 only → `ConnectException`.
- **TEI is the intended production runtime.** HuggingFace Text Embeddings
  Inference (`text-embeddings-inference:cpu-*`, `--model-id Qwen/Qwen3-Embedding-0.6B`)
  produces 1024-dim vectors over `/v1/embeddings`. Document vectors and query
  vectors must come from the same runtime + model revision — mixing runtimes
  silently degrades retrieval. The MLX→TEI migration is tracked in the
  going-public plan (§4.2); a dev endpoint (e.g. omlx) works in the meantime.

## Tooling

```sh
bb test        # run the suite
bb lint        # clj-kondo
bb fmt:check   # cljfmt (use `bb fmt` to apply)
```
