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
- **Code-only repo.** The `kb/` corpus (≈157 nodes from `slayerlabs/slayer`) is
  built locally or on the deploy host from its source repo `slayerlabs/slayer` —
  it is **not shipped here** (`DECISIONS-NEEDED.md` #1). With a corpus present, `bb stats` reports the
  live node count and `bb eval:retrieval` the retrieval scorecard. (Figures are
  regenerable, never transcribed.)

The live MCP surface is exactly five **read** tools — `search`, `get_node`,
`traverse`, `neighbors`, `whats_new`. There are no write tools (ADR-008). The HTTP
transport is **stateless Streamable HTTP**: JSON-RPC over `POST /mcp`, `GET /mcp`
→ 405 (no server-initiated stream), `GET /health` for liveness. It negotiates the
client's `MCP-Protocol-Version` and supports optional `Origin` allowlisting
(`MCP_ALLOWED_ORIGINS`) and static Bearer auth (`MCP_AUTH_TOKEN`). The stdio
transport also works for all clients.

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

Runs on a clean clone with no embedder (FTS + alias + graph). The corpus is not
shipped, so build one from the bundled fixture. See
[`docs/quickstart.md`](docs/quickstart.md):

```sh
bb ingest:repo examples/sample-source   # build kb/ from a tiny demo source
bb index:rebuild                         # derive kb.db from kb/
bb search "DemoEval"                     # ranked nodes, each with a provenance ref
```

## Connect

Point any MCP client at the deployment's `https://<DOMAIN>/mcp`. Per-client,
copy-paste configs (Claude Code, Claude Desktop, Codex, Cursor, the API connector)
are in [`CONNECT.md`](CONNECT.md). The public preview is open (no token); reads
only, provenance-pinned, cross-lingual.

## Self-host

Run the whole stack (prophet + the pinned TEI embedder) with Docker Compose:

```sh
docker compose up            # builds the corpus from the bundled fixture
# MCP at  http://localhost:8765/mcp   (web at http://localhost:8765)
claude mcp add --transport http prophet http://localhost:8765/mcp
```

That default needs zero credentials and serves a 3-node demo corpus in `:mode
:hybrid` (TEI embeds the fixture). To serve the real public corpus, set:

```sh
PROPHET_SOURCE_REPO=https://github.com/slayerlabs/slayer
PROPHET_SOURCE_CONFIG=slayer
```

The bundled `tei` service provides hybrid retrieval out of the box
(`SLAYER_EMBED_URL=http://tei:80`). TEI CPU images are amd64-only; on an arm64 dev
machine the image runs under emulation and TEI inference is unreliable — hybrid is
verified on native amd64 (CI + the deploy host).

## Repo map

| Path | What |
|---|---|
| `src/prophet/` | Clojure source (adapters, extract, resolve, store, index, mcp) |
| `kb/` | the note store (md+YAML) — source of truth; gitignored, built locally/on host |
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
