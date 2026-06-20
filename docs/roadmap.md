# Roadmap

Build in order. Each milestone ships something usable. The sequencing front-loads
value that needs **no ML, no GPU, no training** (plays to a Clojure/full-stack
strength, routes around the "no motivation for training experiments" constraint)
and defers the hard, noisy part (Discord) to last.

---

## v0 — Tier A, repo only (read path)

**Scope**
- `RepoAdapter` (`discover / fetch / changed`) → `RawItem`.
- `page`, `card`, `log`, `config` extractors (deterministic).
- Node store (md+YAML) with stable ULIDs + provenance pinned to commit sha.
- Index: SQLite + FTS5 + sqlite-vec; `bb index:rebuild` from files.
- MCP read tools: `search`, `get_node`, `traverse`, `neighbors`, `whats_new`.

**Out of scope:** Discord, synthesis, write tools, web build.

**Acceptance**
- A cold `bb ingest:repo` produces nodes for every artifact; re-running is a no-op
  (deterministic ids, content-hash skip).
- Deleting the index and running `bb index:rebuild` reproduces it from the md.
- An MCP client can answer, with provenance refs:
  - a glossary/jargon query (e.g. "what is LLMzSzŁ"),
  - a current-leaderboard / dataset-lineage query,
  - a multi-hop query (experiment → dataset → license) via `traverse`.

This alone is a working "ask the lab's artifacts" tool and the core onboarding win.

---

## v0.5 — Glossary, MOC pages, roamable web

**Scope**
- Auto-extract `concept` nodes (glossary) from cards/pages.
- Per-MOC index pages.
- Hugo build of the note store; backlinks + graph view; `visibility` →
  public build now (internal build stubbed).

**Acceptance**
- A newcomer can open a shared link and roam from a concept to the experiments
  and datasets that use it.
- Every rendered claim shows a source link.

---

## v1 — Tier B (Discord) firehose

**Scope**
- `DiscordAdapter` → `RawItem` (`:kind :message`).
- Heuristic stage: noise filter, simhash near-dedup, thread segmentation,
  language detect.
- Local extraction (MLX, small model), strict-JSON schema-validated.
- Resolver: attach-to-anchor vs new vs review, using the alias table.
- `tau_high` / `tau_low` tuned on a real Discord export; eval set documented.

**Acceptance**
- A decision made in chat surfaces as a provenance-bearing observation on the
  correct existing anchor node (e.g. a mix or recipe), not as an orphan.
- Measured attach precision on the eval set is recorded in `decisions.md`.

---

## v1.5 — Synthesis + governance

**Scope**
- `synth/*`: per-node / per-MOC "current state" rollups, recency-weighted,
  provenance-guarded; mark superseded; list open questions.
- Write tools (`write_observation`, `propose_node`, `propose_link`) + review queue.
- Internal Hugo build behind auth.

**Acceptance**
- No synthesized claim lacks a provenance ref (automated check in CI).
- New nodes/links from MCP land in the review queue, never the live graph.
- A periodic human audit step exists for synthesis drift.

---

## Explicitly later / maybe-never

- Code AST parsing beyond stubs.
- Slack adapter (same Tier B path as Discord).
- Multi-writer CRDT (only if concurrent human edits to the same node become real).
- Anything resembling biological decay (see ADR-002 — rejected on purpose).
