# Prophet

A provenance-first knowledge base for the **Slayer** applied-research lab (Polish
LLMs). Prophet ingests heterogeneous sources into a graph of human-readable,
LLM-queryable notes and serves both audiences: humans via a roamable static site,
LLMs via MCP. ("Slayer" is the lab; Prophet is this project.)

**It is:** an ingest pipeline → a linked md+YAML note store (the source of truth)
→ a hybrid retrieval index (SQLite FTS5 + sqlite-vec + graph, fused with
Reciprocal Rank Fusion) → an MCP interface + a Hugo web renderer.

**It is not:** an agent framework, a chatbot, or a decay-based personal memory.
Knowledge is append-only and auditable — nothing is silently forgotten, and every
node and claim carries an exact provenance ref.

## Status

- **Shipped:** v0 (Tier-A repo ingest → index → MCP read tools) and v0.5 (glossary
  concept nodes, roamable Hugo web with provenance→GitHub links, backlinks,
  ego-graphs, public/internal split).
- **Pending:** v1 (Discord / Tier B), v1.5 (synthesis + write tools), and the
  MLX→TEI embedder migration + public deployment (see `DECISIONS-NEEDED.md` and
  the going-public plan).
- The corpus is ≈157 nodes from `slayerlabs/slayer` — run `bb stats` for the live
  count and per-type breakdown, and `bb eval:retrieval` for the retrieval
  scorecard. (Figures here are regenerable, never transcribed.)

The live MCP surface is exactly five **read** tools — `search`, `get_node`,
`traverse`, `neighbors`, `whats_new`. There are no write tools (ADR-008).

## Architecture at a glance

```
Tier A (web+repo) ─┐
                   ├─► Ingest log ─► Heuristics+Extraction ─► Resolve+Link ─► Store(md+YAML)+Index ─► Synthesis ─┬─► MCP (LLM)
Tier B (discord) ──┘   (append-only JSONL)   (dedup, segment,       (embed →          (SQLite FTS+vec)   (rollup,  └─► Web (public/internal)
                                              local LLM)         attach / new node)                      recency)
```

Tier A (curated artifacts) is parsed deterministically into **anchor nodes** —
almost no LLM. Tier B (firehose, planned) attaches as observations to those
anchors. Synthesis and Tier B are planned (v1/v1.5). Full design:
[`docs/architecture.md`](docs/architecture.md).

## Quickstart

Runs on a clean clone with no embedder (FTS + alias + graph). See
[`docs/quickstart.md`](docs/quickstart.md):

```sh
bb index:rebuild          # derive kb.db from the note store
bb search "held-out"      # ranked nodes, each with a provenance ref
```

## Repo map

| Path | What |
|---|---|
| `src/prophet/` | Clojure source (adapters, extract, resolve, store, index, mcp) |
| `kb/` | the note store (md+YAML) — the source of truth |
| `kb.db` | derived SQLite/vector index (rebuildable from `kb/`) |
| `docs/` | design docs (architecture, data-contracts, ingest-repo, roadmap, decisions) |
| `eval/` | retrieval gold set |
| `examples/sample-source/` | tiny throwaway source for the quickstart |
| `web/` | generated Hugo site (rebuildable) |
| `bb.edn` / `deps.edn` | task runner + JVM deps |

## Docs & governance

- Design docs: [`docs/`](docs/) — start with
  [architecture](docs/architecture.md) → [data-contracts](docs/data-contracts.md)
  → [ingest-repo](docs/ingest-repo.md) → [roadmap](docs/roadmap.md) →
  [decisions](docs/decisions.md).
- License: [MIT](LICENSE) (covers the code; `kb/` content is a separate question).
- Open decisions for going public: [`DECISIONS-NEEDED.md`](DECISIONS-NEEDED.md).
- Security: [`SECURITY.md`](SECURITY.md).
