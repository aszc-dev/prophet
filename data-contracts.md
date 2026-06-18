# Data Contracts

These are the load-bearing interfaces. Keep them stable; everything else can change.

---

## 1. `RawItem` — the source-adapter boundary

Every adapter normalizes its source into this shape. Nothing source-specific may
leak past it.

```clojure
{:id           "..."        ; deterministic hash(source + ref); idempotent re-ingest
 :source       :git         ; :git | :web | :discord | ...
 :ref          "..."        ; URL, or repo@sha:path, or channel/msg-id; becomes provenance.ref
 :kind         :log         ; :page | :card | :log | :config | :code | :data | :message
 :title        "..."
 :body         "..."        ; markdown / text (may be empty for :data)
 :meta         {...}        ; parsed frontmatter / structured fields if present
 :content-hash "sha256:..." ; for incremental sync; unchanged hash => skip
 :fetched-at   "2026-06-18T...Z"}
```

Rules: `:id` deterministic (re-runs do not duplicate). `:content-hash` drives
incremental sync. `:ref` is the exact provenance pointer carried downstream.

---

## 2. Node — the unit of knowledge (md + YAML)

Canonical on-disk form. The YAML frontmatter is machine-parseable; the body is
human prose. Both org-roam (personal) and the engine read the same `id`.

```yaml
---
id: 01J8ZK...                 # ULID, stable forever — the join key everywhere
type: experiment              # experiment|dataset|mix|benchmark|axis|model|recipe|decision|concept|person|question|source
title: "slayer-style-27b: smak bez amnezji"
status: current               # current | superseded | draft | archived
superseded_by: null
moc: [trening, styl]          # top-level facets = site sections / channels
tags: [dpo, style-sft, regression-gate]
visibility: public            # public | internal
provenance:                   # MANDATORY, >=1 entry; no provenance => node may not exist
  - {source: web,     ref: "https://slayer.fabryka.ai/eksperymenty#styl-27b"}
  - {source: git,     ref: "slayerlabs/recipes@a1b2c3d:configs/style27b.yaml"}
  - {source: discord, ref: "slayer/trening-modelu/123456789"}
links:                        # typed edges; values are node ids
  uses-dataset: [01J8...style-sft-1.6k]
  measured-by:  [01J8...llmzszl]
  decided-in:   [01J8...keep-style]
  supersedes:   []
embedding_hash: "sha256:..."  # invalidates the vector index entry on change
updated: 2026-06-17T14:02:00Z
---

## State (synthesized — claims only from the observations below)
...

## Observations (append-only; every line carries a ref)
- 2026-06-17 [discord:.../123] LLMzSzŁ 65.0 vs base 58.5 (likelihood, n=400)
```

### `content_hash` — canonical, representation-independent

`content_hash` drives incremental sync: an unchanged hash skips the write
(`:unchanged`). It is computed over the meaningful payload only (`type`, `title`,
`status`, `provenance`, `links`, `moc`, `tags`, `aliases`, `definition`,
`observations`) — never over `updated`, `id`, or the derived hashes themselves.

The payload is first reduced to a **canonical form** so the hash does not flip
between the two shapes a node legitimately takes:

- **fresh extraction** — keyword keys/values (`:source :git`), Clojure vectors,
  plain maps; and
- **clj-yaml parse-back** — string scalars (`"git"`), `LazySeq`, `OrderedMap`.

Canonicalization: keywords (keys and values) → their name; non-vector seqs →
vectors; maps → key-sorted. Reference impl: `store.node/canonical`.

> **Rule for any NEW source adapter (Discord next):** hash via this same canonical
> form, never the raw shape. Skipping it reintroduces the multi-source re-ingest
> churn (keyword-vs-string, LazySeq-vs-vector) that #1's no-op re-ingest depends
> on. A re-ingest that is not a verified no-op (`unchanged N`) is the symptom.

### Node types

`experiment`, `dataset`, `mix`, `benchmark`, `axis`, `model`, `recipe`,
`decision`, `concept` (glossary), `person`, `question`, `source`.

### Relation vocabulary (typed link `rel` values)

`derives-from`, `supersedes`, `measured-by`, `uses-dataset`, `uses-recipe`,
`decided-in`, `owned-by`, `contradicts`, `mentions`, `defines`.

---

## 3. Extraction output — what the local LLM returns per segment

Strict JSON, schema-validated. One segment in, one object out. No prose, no
markdown fences.

```json
{
  "segment_id": "...",
  "node_type": "experiment",
  "title": "...",
  "atomic_claims": [
    {"text": "...", "provenance_ref": "discord:slayer/trening-modelu/123"}
  ],
  "entities": [
    {"name": "DPO", "type": "concept"},
    {"name": "style-sft-1.6k", "type": "dataset"}
  ],
  "proposed_relations": [
    {"rel": "uses-dataset", "target_hint": "style-sft-1.6k"}
  ],
  "confidence": 0.0
}
```

Every `atomic_claim` MUST carry a `provenance_ref`. Claims without one are
dropped, not stored.

---

## 4. Index — derived SQLite (rebuildable from the note store)

```sql
CREATE TABLE nodes (
  id             TEXT PRIMARY KEY,   -- ULID
  type           TEXT NOT NULL,
  title          TEXT NOT NULL,
  status         TEXT NOT NULL,      -- current|superseded|draft|archived
  visibility     TEXT NOT NULL,      -- public|internal
  moc            TEXT,               -- json array
  path           TEXT NOT NULL,      -- file path in the note store
  embedding_hash TEXT,
  updated        TEXT NOT NULL
);

CREATE TABLE links (
  src_id TEXT NOT NULL,
  rel    TEXT NOT NULL,
  dst_id TEXT NOT NULL,
  PRIMARY KEY (src_id, rel, dst_id)
);

CREATE TABLE provenance (
  node_id TEXT NOT NULL,
  source  TEXT NOT NULL,
  ref     TEXT NOT NULL,
  PRIMARY KEY (node_id, ref)
);

CREATE TABLE aliases (              -- entity resolution table; cards seed canonical names
  alias   TEXT NOT NULL,
  node_id TEXT NOT NULL,
  PRIMARY KEY (alias, node_id)
);

-- keyword / lexical
CREATE VIRTUAL TABLE nodes_fts USING fts5(title, body, content='');

-- semantic (sqlite-vec). Dimension matches the embedding model.
CREATE VIRTUAL TABLE vec_nodes USING vec0(node_id TEXT, embedding FLOAT[1024]);
```

The index holds no truth: drop it and `bb index:rebuild` reconstructs it from
the md files.

---

## 5. MCP tools — the LLM interface

Verbs and semantics; parameter detail can be refined during implementation.

| Tool | Purpose | Side effects |
|---|---|---|
| `search(query, filters?)` | Hybrid FTS + vector + graph; returns node refs + snippets | none |
| `get_node(id)` | Full node (frontmatter + body) | none |
| `traverse(id, rel?, depth?)` | Follow typed links, multi-hop | none |
| `neighbors(id)` | Adjacent nodes | none |
| `whats_new(since, moc?)` | Recency feed for situational awareness | none |
| `write_observation(node_id, text, provenance)` | Append an observation | append-only; `provenance` required |
| `propose_node(draft)` | Submit a candidate new node | enters review queue, not live graph |
| `propose_link(src, rel, dst)` | Submit a candidate edge | enters review queue |

Read/write split is intentional: reads are open; writes are provenance-gated;
new nodes and links land in a **review queue**, not directly in the shared graph.
At ~162 contributors, auto-writing new nodes to the canonical graph invites junk.
