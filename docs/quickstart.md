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
| omlx | recent | local embedder for hybrid mode on Apple Silicon (ADR-013); optional |
| Claude Code | recent | only to register the MCP server (`claude mcp add`) |
| pre-commit | 4.x | optional, for the commit hooks |

**Native-lib note (`vec0`).** The sqlite-vec loadable extension is vendored under
`resources/native/` for macOS arm64, linux-x86_64, and linux-aarch64;
`index/db.clj` picks the right one by `{os,arch}` at runtime. So the index layer
runs on Apple Silicon dev and the Linux deploy host alike.

## Runtime split (ADR-006)

sqlite-vec loads only under a full JVM, never under Babashka. So every
index-touching `bb` task shells out to `clojure -M:run <cmd>`. Pure transforms
(`extract/*`, `resolve/*`, adapters) are runtime-agnostic.

## The note store is not shipped (code-only)

This repo is **code-only** (`DECISIONS-NEEDED.md` #1): the real `kb/` corpus is
built locally or on the deploy host from its source repo (`slayerlabs/slayer`),
and `kb/` is gitignored. A fresh clone has no corpus — you build one by ingesting
a source.

## Smallest end-to-end loop (no embedder)

A tiny throwaway source ships at `examples/sample-source/` (a card, a config, a
content page) so the loop runs on a clean clone:

```sh
# optional: install commit hooks
pre-commit install

# 1. ingest a source into kb/ (deterministic; re-running is a no-op)
bb ingest:repo examples/sample-source

# 2. derive the index from kb/
bb index:rebuild

# 3. search — ranked nodes, each with a provenance ref
bb search "DemoEval"

# corpus summary (machine-readable EDN)
bb stats
```

`bb search` returns nodes whose observations carry `git:<repo>@<sha>:<path>`
provenance refs. No network, no GPU, no model required. Point `bb ingest:repo` at
a real source repo (e.g. a clone of the lab's content) to build the full corpus;
`bb eval:retrieval` / `bb eval:gate` then score it against `eval/retrieval-gold.edn`
(the gold set references real corpus node ids, so it is meaningful only with the
real corpus present).

## Enabling hybrid retrieval (local omlx, ADR-013)

The demo/preview embedder is the **local omlx** server (MLX on Apple Silicon),
exposing an OpenAI-compatible `/v1/embeddings`. Start `omlx serve` with the
embedding model loaded, point the client at it, then rebuild — `bb index:rebuild`
embeds the corpus and search switches to `:hybrid`.

```sh
export SLAYER_EMBED_URL=http://127.0.0.1:10240       # the omlx server
export SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit  # 1024-dim
export SLAYER_EMBED_API_KEY=<omlx-key>               # omlx requires a bearer token
bb index:rebuild   # -> {... :embedded <n> :mode :hybrid}
```

- **Use `127.0.0.1`, not `localhost`.** The JVM HttpClient resolves `localhost`
  to IPv6 `::1`; omlx binds IPv4 only → `ConnectException`.
- **One runtime for both lanes.** Document vectors and query vectors must come
  from the same runtime + model — mixing runtimes silently degrades retrieval
  (cosine can fall below 0.2 for the same text). The dimension is pinned at 1024.
- **Hosted runtime is parked.** A future public HTTP MCP repins this to a
  Slayer-hosted embedder (TEI/Gemini, the `docker-compose.yml` path); switching is
  just a re-embed + `bb index:rebuild`, no store change (ADR-013).

## Use it from Claude (MCP onboarding)

The demo serves the MCP over **stdio**. Register it once with `claude mcp add`
(user scope = available in every project); pass the omlx env so the vector lane
works at query time. Replace `/path/to/prophet` and the omlx key:

```sh
claude mcp add slayer-kb -s user \
  --env SLAYER_EMBED_URL=http://127.0.0.1:10240 \
  --env SLAYER_EMBED_API_KEY=<omlx-key> \
  --env SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit \
  -- /bin/sh -c 'cd /path/to/prophet && exec "$(command -v clojure)" -M:run serve-mcp'
```

The `cd` is required: the server resolves `deps.edn` and `kb.db` from the project
dir, and a user-scope server may launch from any cwd. Verify:

```sh
claude mcp get slayer-kb     # Status: ✔ Connected
```

Five read tools are exposed (`search`, `get_node`, `traverse`, `neighbors`,
`whats_new`); reads are open, there are no write tools (ADR-008). This build reads
MCP config via `claude mcp …`, not `claude_desktop_config.json`.

## Tooling

```sh
bb test        # run the suite
bb lint        # clj-kondo
bb fmt:check   # cljfmt (use `bb fmt` to apply)
```
