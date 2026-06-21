# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prophet

Prophet is a shared, provenance-first knowledge base for the **Slayer**
applied-research lab (Polish LLMs). It ingests heterogeneous sources into a graph
of human-readable, LLM-queryable notes and serves both audiences: humans via a
roamable static web, LLMs via MCP. ("Slayer" is the lab; Prophet is this project.)

Source priority: **curated artifacts first** (the lab's Hugo site + the
`slayerlabs` repos), **firehose later** (Discord, possibly Slack).

## What this is / isn't

- **IS:** an ingest pipeline + a linked md+YAML note store + a hybrid retrieval
  index + an MCP interface + a static web renderer.
- **ISN'T:** an agent framework, a chatbot, or a personal-memory store with
  decay. Memory here is append-only and auditable; nothing is silently forgotten.

## Non-negotiable invariants (do not violate)

1. **Files are the source of truth.** The md+YAML notes are canonical. The
   SQLite/vector index is *derived* and must be fully rebuildable from the files.
   Never make the index authoritative.
2. **Provenance or it doesn't exist.** Every node and every synthesized claim
   links to its source(s) via exact refs (e.g.
   `git:slayerlabs/<repo>@<sha>:<path>#<section>`). No provenance → do not write it.
   This mirrors the lab's own culture ("held-out albo nic", "lineage i disclosure").
3. **Append-only; supersede with a pointer.** Never overwrite or hard-delete
   knowledge. Updates append observations; replaced facts get `status: superseded`
   plus `superseded_by`. A removed source → `status: archived`, never deleted.
4. **Deterministic first, LLM last.** Use parsers/heuristics wherever the source
   already encodes structure. Invoke the (local, small) LLM only to fill genuine
   gaps. Tier A (curated artifacts) should need almost no LLM.
5. **Stable IDs.** Every node has a ULID that never changes. It is the join key
   across the file store, the index, and the org-roam mirror.

## Stack

- Pipeline & tooling: **Clojure + Babashka**. Pure functions for transforms; IO
  (git, http, sqlite, fs) isolated at the edges.
- Index: **SQLite** with **FTS5** + **sqlite-vec**.
- Embeddings: **local omlx** server (Apple Silicon GPU), the demo-phase runtime
  (ADR-013); model `Qwen3-Embedding-0.6B-8bit` (1024 dims), OpenAI `/v1/embeddings`.
  Hosted/online inference is deferred (ADR-013); the TEI/Gemini container path is parked.
- Extraction LLM (glossary gap-fill, optional): a small local chat endpoint
  (Qwen3 4–8B class), strict JSON, schema-validated.
- Web: **Hugo** (reuses the lab's existing toolchain). `visibility` flag → two
  builds (public site + internal site behind auth).
- Personal layer: **org-roam** mirror sharing the same stable IDs.
- Interface: **MCP server** (Clojure).

## Current state (2026-06-18)

**v0 + v0.5 implemented.** Git repo: **github.com/aszc-dev/prophet** (private),
branch `main`. The design docs live
under `docs/` (`architecture`, `data-contracts`, `ingest-repo`, `roadmap`,
`decisions`, plus `v0.5-brief`). Code is under `src/prophet/`. The note store
`kb/` is **gitignored** — this is a code-only repo (ADR-014); `kb/` is
built locally or on the deploy host from its source repo `slayerlabs/slayer`
(`bb ingest:repo`), then
`bb stats` reports the live node count (≈157 from `slayerlabs/slayer`). `kb.db` is
the derived index; `web/` is the Hugo site; `fixtures/recipes/` and
`examples/sample-source/` are test sources (the latter tracked, for the quickstart).

v0.5 adds: glossary concept nodes (`bb glossary:build`) and a roamable Hugo web
(`bb web:build`) with provenance→GitHub links, backlinks, ego-graphs, MOC pages,
public/internal split.

What works end-to-end (all tests green — `bb test`):
- `bb ingest:repo <repo>` — RepoAdapter -> per-kind extractors (`log` `page` `card`
  `config`) -> resolver (exact/structural links) -> md+YAML store. Deterministic:
  cold ingest creates, re-run is a no-op (stable ULIDs via `source_key`, content-hash skip).
- `bb index:rebuild` — derives `kb.db` (SQLite FTS5 + sqlite-vec) from `kb/`; fully
  rebuildable.
- `bb serve:mcp` — MCP stdio server, 5 read tools (`search` `get_node` `traverse`
  `neighbors` `whats_new`). Reads open, no write tools (ADR-008).

**Runtime split (ADR-006).** sqlite-vec loads only on the JVM, never under
Babashka. So `index/*` + MCP run on the JVM (`clojure -M:run <cmd>`); bb.edn tasks
shell out to it. Adapters and pure transforms (`extract/*`, `resolve/*`) are
runtime-agnostic.

**Embeddings** (ADR-009 dim pin + ADR-013 runtime): with no endpoint the vector
lane is inert (weight 0) — search is FTS + exact-alias + graph. Point the client
at the **local omlx** server and `bb index:rebuild` to switch to `:hybrid`:
- `SLAYER_EMBED_URL` — `http://127.0.0.1:10240` (the omlx server). Use `127.0.0.1`,
  not `localhost` (the JVM HttpClient resolves `localhost` to IPv6 `::1`; omlx binds
  IPv4 only → `ConnectException`). The client appends `/v1/embeddings`.
- `SLAYER_EMBED_MODEL` — default `Qwen3-Embedding-0.6B-8bit` (1024 dims).
- `SLAYER_EMBED_API_KEY` — the omlx bearer token (omlx requires one).

The endpoint must expose OpenAI-compatible `/v1/embeddings` (omlx does; plain
`mlx_lm.server` does not). Dimension pinned at 1024; the client omits `:dimensions`
at native width. `embed/*disabled*` binds inert for hermetic tests. The model +
runtime are the contract — vectors are not interchangeable across runtimes.

The demo/preview serves **MCP over stdio** on macOS (`bb serve:mcp`, register with
`claude mcp add`). The containerized path (Docker + TEI/Gemini) is parked
for the future hosted phase (ADR-013).

**Shipped:** v0 (Tier-A repo ingest → index → MCP) and v0.5 (glossary concept
nodes via `bb glossary:build`, roamable Hugo web via `bb web:build`). Demo/preview
runs on macOS with the local omlx embedder and stdio MCP (ADR-013); the index is
`:hybrid` against the omlx 1024-d baseline. **Parked:** hosted/online inference and
the containerized deployment (Docker + TEI/Gemini) — deferred to Slayer
(ADR-013). **Not yet done:** Discord/Tier B (v1); synthesis + write tools (v1.5).

## Repo layout

```
prophet/                    # repo (project: Prophet)
  CLAUDE.md                 # this file
  docs/                     # design docs (architecture, data-contracts, ingest-repo,
                            #   roadmap, decisions, quickstart, v0.5-brief)
  src/prophet/              # Clojure source
    main.clj                # JVM CLI entrypoint (bb tasks shell out here)
    ingest.clj              # ingest driver; load-config per source
    glossary.clj            # glossary concept nodes + grounded defs
    provenance.clj          # provenance ref -> source URL (single source of truth)
    util.clj                # ULIDs, slugs
    adapters/               # source adapters (repo, later discord) -> RawItem
    extract/                # per-kind extractors
    resolve/                # entity resolution + linking
    store/                  # md+YAML read/write
    index/                  # sqlite + fts + vec (db, schema, query, embed, chat)
    mcp/                    # MCP server: server.clj (stdio) + http.clj (HTTP transport)
    web/                    # Hugo site generator (build.clj)
    eval/                   # fidelity + retrieval scorecards
    synth/                  # synthesis / rollup (planned, v1.5 — not built)
  eval/                     # retrieval gold set (retrieval-gold.edn)
  web/                      # Hugo config/layouts; content/ + public/ are generated
  examples/sample-source/   # tracked tiny source for the quickstart / CI fixture
  Dockerfile                # build + serve image (parked hosted path)
  deploy/entrypoint.sh      # pipeline (ingest -> ... -> serve)
  bb.edn / deps.edn         # tasks + JVM deps
  kb/                       # the note store (md+YAML) — source of truth; gitignored (code-only)
```

## Commands

Defined in `bb.edn` (run `bb tasks` for the full list). The pipeline-facing ones:

- `bb ingest:repo <repo> [config]` — incremental ingest of a source repo into `kb/`
- `bb glossary:build` — derive glossary concept nodes from `kb/`
- `bb index:rebuild` — rebuild the SQLite/vector index from `kb/`
- `bb web:build` — Hugo build (emits public + internal)
- `bb serve:mcp` — start the MCP server (stdio)
- `bb serve:mcp-http` — start the MCP server over HTTP (POST /mcp, GET /health)
- `bb search <terms>` — query the index from the CLI
- `bb stats` — machine-readable corpus summary (node count + per-type)
- `bb eval:retrieval` / `bb eval:gate` — retrieval scorecard + regression gate
- `bb smoke` / `bb test` / `bb lint` / `bb fmt` / `bb fmt:check` — checks

## Where to start

Read the design docs under `docs/` in this order: **architecture → data-contracts →
ingest-repo → roadmap → decisions.** Build strictly in roadmap milestone order. Do **not** skip
v0 (Tier A, repo only) to chase Discord — v0 bootstraps the canonical entity
vocabulary that later sources depend on, and it delivers onboarding value with no
ML, no GPU, no training.

## Conventions

- All docs, code, and comments in English.
- The source-adapter boundary is `RawItem`. Nothing source-specific may leak past
  it. A new source = a new adapter, zero changes downstream.
- Validate every LLM JSON response against its schema; on failure, retry once,
  then route the segment to a review queue. Never let unvalidated model output
  enter the store.
