# Architecture

## Goal

Turn a fast-moving research community's scattered knowledge (curated artifacts +
chat) into one append-only, provenance-first graph that both humans and LLMs can
query. Primary job-to-be-done: **onboarding and situational awareness** — let a
newcomer (or a maintainer who can't track every thread) answer "what's the
current state of X, decided where, measured how, by whom" without reading
backscroll.

## Two source tiers

The central design distinction. Sources are not uniform; treat them as two classes.

- **Tier A — curated artifacts (high signal).** The lab's Hugo site and the
  `slayerlabs` repos. Already semi-structured (frontmatter, cards, logs, configs).
  Parsed deterministically into **anchor nodes** — the skeleton of the graph.
  Almost no LLM.
- **Tier B — firehose (low signal).** Discord (later Slack). High noise. Passes
  heavy heuristic filtering, then a small local LLM for extraction, then
  **attaches as observations to existing Tier A anchors** via entity resolution.

Why order matters: Tier A first not only because it is easy, but because the
anchor nodes (especially dataset/benchmark/model **cards**) define the canonical
names that Tier B resolution depends on. Repo-first bootstraps the vocabulary.

## Pipeline (single spine, two entry lanes)

```
Tier A (web+repo) ─┐
                   ├─► Ingest log ─► Heuristics+Extraction ─► Resolve+Link ─► Store(md+YAML)+Index ─► Synthesis ─┬─► MCP (LLM)
Tier B (discord) ──┘     (append-only JSONL)   (dedup, segment,        (embed →           (SQLite FTS+vec)    (rollup,    └─► Web (roamable, public/internal)
                                                local LLM MLX)       attach / new node)                       recency)
```

Tier A largely skips the heavy heuristic + LLM extraction step (its structure is
already author-provided); Tier B is the reason that step exists.

## Components and responsibilities

| Component | Responsibility | Notes |
|---|---|---|
| `adapters/*` | `discover / fetch / changed` per source → `RawItem` | The seam. Repo first; Discord conforms later. |
| `ingest-log` | Append-only JSONL of every `RawItem` event | Event-sourced; replayable; never mutated. |
| `extract/*` | `RawItem` → candidate nodes/observations | Per-`kind` extractors; deterministic where possible. |
| `resolve/*` | Decide attach-to-existing vs new node; canonicalize entities; append typed relations | Embedding similarity + alias table. |
| `store/*` | Read/write md+YAML notes; the source of truth | Stable ULIDs; wikilinks; org-roam-compatible frontmatter. |
| `index/*` | Derived SQLite (FTS5) + vector (sqlite-vec) | Rebuildable from `store/` at any time. |
| `synth/*` | Per-MOC "current state" rollups; mark superseded; surface open questions | LLM-driven, scheduled; recency-weighted; provenance-guarded. |
| `mcp/*` | Expose read/traverse/write tools to LLM clients | Read open; write provenance-gated; new nodes → review. |
| web (Hugo) | Render `store/` to a roamable static site | Two builds via `visibility`. |

## Retrieval model

Hybrid, combined behind a single `search`:

1. **FTS5 (BM25)** — keyword / lexical.
2. **Vector (sqlite-vec, cosine)** — semantic similarity.
3. **Graph traversal** — typed links, multi-hop.

Pure vector RAG is competitive only on flat semantic lookups. This corpus
(decisions in chat → experiment log → dataset card → repo commit) is relational
by construction, so graph traversal is load-bearing, not decorative.

## Memory model

Append-only. Three write shapes:

- **new node** — a new entity/decision/concept not yet present.
- **observation** — a timestamped, provenance-bearing line appended under a node.
- **supersede** — set `status: superseded` + `superseded_by` on the old node;
  the old node and its history remain.

No decay, no pruning, no silent forgetting. History and lineage are the product.

## Synthesis (the onboarding payload)

A scheduled job re-derives, per node (and per MOC), a short "current state"
section **only from that node's cited observations**. It must:

- weight recent observations higher (recency), but never delete old ones;
- mark facts contradicted by newer, higher-provenance observations as superseded;
- list unresolved questions explicitly;
- emit no claim that lacks a provenance ref (hard guardrail).

This is where most hallucination risk lives → see ADR on provenance + the review
queue.

## Interfaces

- **MCP** for LLMs. **Implemented today (v0):** five read tools — `search`,
  `get_node`, `traverse`, `neighbors`, `whats_new`. Reads are open; there are no
  write tools (ADR-008). **Planned (v1.5, not implemented):** `write_observation`,
  `propose_node`, `propose_link` — provenance-gated writes whose new nodes/links
  land in a review queue rather than the live graph. (Signatures in
  data-contracts.md.)
- **Web** for humans: Hugo renders the note store. `visibility: public` → public
  build; `visibility: internal` → auth-gated build. Backlinks + a graph view make
  it roamable; share-by-link is just a node URL.

## Extensibility

A new source is a new `adapters/` implementation emitting `RawItem`. Tier-A-like
(structured) sources reuse deterministic extractors; Tier-B-like (chat) sources
reuse the heuristic + LLM path and the resolver. The downstream stages
(resolve/store/index/synth/mcp/web) never change.
