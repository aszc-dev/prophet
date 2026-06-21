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
- **Hosted MVP (ADR-015):** a public, open, read-only HTTP MCP is live at
  **`https://prophet.aszc.dev/mcp`** — FTS-only (no embedder), corpus rebuilt at boot
  from the public `slayerlabs/slayer`. Connect any MCP client in one line (see
  [Connect](#connect)); no token. The vector lane (a hosted embedder) is Phase 2.
- **Demo/preview (ADR-013):** runs on macOS with the local **omlx** embedder and a
  **stdio** MCP — build a corpus, `claude mcp add`, and go (see [Connect](#connect)).
  Hosted online inference (the embedder) is deferred to Slayer.
- **Pending:** v1 (Discord / Tier B), v1.5 (synthesis + write tools).
- **Code-only repo.** The `kb/` corpus (≈157 nodes from `slayerlabs/slayer`) is
  built locally or on the deploy host from its source repo `slayerlabs/slayer` —
  it is **not shipped here** (see ADR-014 in [`docs/decisions.md`](docs/decisions.md)). With a corpus present, `bb stats` reports the
  live node count and `bb eval:retrieval` the retrieval scorecard. (Figures are
  regenerable, never transcribed.)

The live MCP surface is exactly five **read** tools — `search`, `get_node`,
`traverse`, `neighbors`, `whats_new`. There are no write tools (ADR-008). The
demo/preview serves these over the **stdio** transport (`bb serve:mcp`; ADR-013); the
hosted MVP serves them over **HTTP** (ADR-015). The **HTTP** transport is **stateless
Streamable HTTP**: JSON-RPC over `POST /mcp`, `GET /mcp` → 405 (no server-initiated
stream), `GET /health` for liveness; it negotiates the client's
`MCP-Protocol-Version` and supports optional `Origin` allowlisting
(`MCP_ALLOWED_ORIGINS`) and static Bearer auth (`MCP_AUTH_TOKEN`) — both left **off**
on the hosted MVP, which is open over public data.

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

**Hosted (open, no setup): one line, no token.** The public FTS-only endpoint is live
(ADR-015):

```sh
claude mcp add --transport http slayer-kb https://prophet.aszc.dev/mcp
```

Any MCP client works the same way — per-client configs in [`CONNECT.md`](CONNECT.md).
Reads only, provenance-pinned, cross-lingual; no auth.

**Local (demo/preview): stdio + hybrid.** For the vector lane, build a corpus
(Quickstart above), start the local omlx embedder, then register the stdio server
(replace `/path/to/prophet` and the omlx key):

```sh
claude mcp add slayer-kb -s user \
  --env SLAYER_EMBED_URL=http://127.0.0.1:10240 \
  --env SLAYER_EMBED_API_KEY=<omlx-key> \
  --env SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit \
  -- /bin/sh -c 'cd /path/to/prophet && exec "$(command -v clojure)" -M:run serve-mcp'
```

## Self-host

**Hosted MVP — single FTS-only container (current, ADR-015).** `docker-compose.yml`
builds the `Dockerfile` image, rebuilds the corpus at boot from the public
`slayerlabs/slayer`, and serves MCP-HTTP + the static web on one port. No embedder
(vector lane inert), public and open. This is what runs at `prophet.aszc.dev`.

**Demo/preview — macOS + omlx (ADR-013).** No containers: install the JVM/Clojure
toolchain, run the local omlx embedder, then ingest → index → serve over stdio for the
**hybrid** (vector-enabled) experience. Full steps in
[`docs/quickstart.md`](docs/quickstart.md).

**Phase 2 — hosted embedder.** Turning the vector lane on (a CPU TEI sidecar or a hosted
API) is deferred to Slayer; omlx is Apple-Silicon-only and cannot run in the Linux
container (ADR-010, ADR-013).

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
- Security: [`SECURITY.md`](SECURITY.md).
