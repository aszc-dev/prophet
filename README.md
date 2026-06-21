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
- **Demo/preview (ADR-013):** runs on macOS with the local **omlx** embedder and a
  **stdio** MCP — build a corpus, `claude mcp add`, and go (see [Connect](#connect)).
  Hosted online inference + a public HTTP deployment are deferred to Slayer.
- **Pending:** v1 (Discord / Tier B), v1.5 (synthesis + write tools). Going-public
  decisions: `DECISIONS-NEEDED.md`.
- **Code-only repo.** The `kb/` corpus (≈157 nodes from `slayerlabs/slayer`) is
  built locally or on the deploy host from its source repo `slayerlabs/slayer` —
  it is **not shipped here** (`DECISIONS-NEEDED.md` #1). With a corpus present, `bb stats` reports the
  live node count and `bb eval:retrieval` the retrieval scorecard. (Figures are
  regenerable, never transcribed.)

The live MCP surface is exactly five **read** tools — `search`, `get_node`,
`traverse`, `neighbors`, `whats_new`. There are no write tools (ADR-008). The
demo/preview serves these over the **stdio** transport (`bb serve:mcp`; ADR-013).
The **HTTP** transport — used by the parked hosted deployment — is **stateless
Streamable HTTP**: JSON-RPC over `POST /mcp`, `GET /mcp` → 405 (no server-initiated
stream), `GET /health` for liveness; it negotiates the client's
`MCP-Protocol-Version` and supports optional `Origin` allowlisting
(`MCP_ALLOWED_ORIGINS`) and static Bearer auth (`MCP_AUTH_TOKEN`).

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

**Current (demo/preview): local stdio.** Build a corpus (Quickstart above), start
the local omlx embedder, then register the stdio server with Claude in one line
(replace `/path/to/prophet` and the omlx key):

```sh
claude mcp add slayer-kb -s user \
  --env SLAYER_EMBED_URL=http://127.0.0.1:10240 \
  --env SLAYER_EMBED_API_KEY=<omlx-key> \
  --env SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit \
  -- /bin/sh -c 'cd /path/to/prophet && exec "$(command -v clojure)" -M:run serve-mcp'
```

Per-client copy-paste configs (stdio now; HTTP for the parked hosted deployment) are
in [`CONNECT.md`](CONNECT.md). Reads only, provenance-pinned, cross-lingual.

**Parked:** a public HTTP endpoint (`https://<DOMAIN>/mcp`) returns once Slayer hosts
the embedder (ADR-013).

## Self-host

**Demo/preview — macOS + omlx (current, ADR-013).** No containers: install the
JVM/Clojure toolchain, run the local omlx embedder, then ingest → index → serve over
stdio. Full steps in [`docs/quickstart.md`](docs/quickstart.md).

**Parked — Docker Compose + hosted embedder (future).** The containerized stack
(prophet + a hosted embedder, MCP-HTTP + static web in one image, `docker-compose.yml`)
is kept for when Slayer provides online inference. omlx is Apple-Silicon-only, so it
cannot run in the Linux container — the container path is inherently the
hosted-inference path, and it returns with the public deployment (ADR-013).

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
