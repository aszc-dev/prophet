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
- Local inference: **MLX**, OpenAI-compatible endpoints — embeddings
  (`qwen3-embeddings-mlx`) and a small extraction LLM (`vllm-mlx` / `mlx-serve`,
  Qwen3 4–8B class). Strict JSON output, schema-validated.
- Web: **Hugo** (reuses the lab's existing toolchain). `visibility` flag → two
  builds (public site + internal site behind auth).
- Personal layer: **org-roam** mirror sharing the same stable IDs.
- Interface: **MCP server** (Clojure).

## Current state (2026-06-18)

**v0 + v0.5 implemented.** Git repo: **github.com/aszc-dev/prophet** (private),
branch `main`. See `docs/dev-log.md` for the development log. The design docs live
under `docs/` (`architecture`, `data-contracts`, `ingest-repo`, `roadmap`,
`decisions`, plus `v0.5-brief`). Code is under `src/prophet/`; the note store is
`kb/` (run `bb stats` for the live node count + per-type breakdown — currently 157
nodes from `slayerlabs/slayer`); `kb.db` is the derived index; `web/` is the Hugo
site; `fixtures/recipes/` is a local, gitignored test source repo.

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

**Embeddings** (ADR-009): with no endpoint the vector lane is inert (weight 0) —
search is FTS + exact-alias + graph. Set these and `bb index:rebuild` to switch to
`:hybrid` (vector lane proven working — an English paraphrase query retrieves the
Polish-titled node by cosine):
- `SLAYER_EMBED_URL` — e.g. `http://127.0.0.1:10240` (omlx). Use `127.0.0.1`, not
  `localhost`: the JVM HttpClient resolves `localhost` to IPv6 `::1` and the MLX
  servers bind IPv4 only → `ConnectException`.
- `SLAYER_EMBED_API_KEY` — bearer token if the server requires one (omlx does).
- `SLAYER_EMBED_MODEL` — default `mlx-community/Qwen3-Embedding-0.6B-8bit` (1024 dims).

The embedding server must expose OpenAI-compatible `/v1/embeddings`. `mlx_lm.server`
does **not** (only completions); use **omlx** (`omlx serve`, model symlinked under
`~/.omlx/models/<org>/<name>/`). Dimension pinned at 1024 (`embed/*disabled*` binds
inert for hermetic tests).

**Shipped:** v0 (Tier-A repo ingest → index → MCP) and v0.5 (glossary concept
nodes via `bb glossary:build`, roamable Hugo web via `bb web:build`).
**Not yet done:** Discord/Tier B (v1); synthesis + write tools (v1.5); the
MLX→TEI embedder migration and hybrid-blend tuning (the going-public plan; needs
the TEI endpoint + real data).

## Repo layout

```
prophet/                    # repo (project: Prophet)
  CLAUDE.md                 # this file
  docs/                     # design docs
    architecture.md         # system design, tiers, pipeline stages
    data-contracts.md       # RawItem, Node schema, extraction JSON, SQLite DDL, MCP tools
    ingest-repo.md          # repo -> knowledge in detail; source-adapter seam
    roadmap.md              # milestones v0 -> v1.5 with acceptance criteria
    decisions.md            # ADRs (settled design decisions + rationale)
    v0.5-brief.md           # v0.5 scoping brief
    dev-log.md              # development log (session narration)
  src/prophet/              # Clojure source
    adapters/               # source adapters (repo, later discord) -> RawItem
    extract/                # per-kind extractors
    resolve/                # entity resolution + linking
    store/                  # md+YAML read/write
    index/                  # sqlite + fts + vec
    mcp/                    # MCP server + tools
    synth/                  # synthesis / rollup (planned, v1.5 — not built)
  bb.edn                    # Babashka tasks
  kb/                       # the note store (md+YAML) — the source of truth
```

## Commands

Defined in `bb.edn` (run `bb tasks` for the full list). The pipeline-facing ones:

- `bb ingest:repo <repo>` — incremental ingest of a source repo into `kb/`
- `bb glossary:build` — derive glossary concept nodes from `kb/`
- `bb index:rebuild` — rebuild the SQLite/vector index from `kb/`
- `bb web:build` — Hugo build (emits public + internal)
- `bb serve:mcp` — start the MCP server (stdio)
- `bb search <terms>` — query the index from the CLI
- `bb stats` — machine-readable corpus summary (node count + per-type)
- `bb eval:retrieval` / `bb eval:gate` — retrieval scorecard + regression gate

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
