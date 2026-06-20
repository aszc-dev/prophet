# Ingest: Repo → Knowledge

How the first source (the `slayerlabs` repos + the Hugo site that lives in them)
becomes nodes. Written so future sources (Discord) plug in without touching
downstream stages.

## 0. The source-adapter seam (build this first)

Every source implements one interface. `RawItem` (see data-contracts.md) is the
boundary; nothing source-specific crosses it.

```clojure
(defprotocol SourceAdapter
  (discover [this]        "-> seq of refs that exist")
  (fetch    [this ref]    "-> RawItem")
  (changed  [this since]  "-> seq of refs changed since a cursor"))
```

`RepoAdapter` is the first implementation. `DiscordAdapter` will be the second
and must require zero changes to `extract / resolve / store / index / synth / mcp`.

## 1. Discovery & classification

1. Clone or pull the source repo. Record `HEAD` sha as the ingest cursor.
2. Walk the tree, honoring `.gitignore` and a project `.kbignore`.
3. Classify each file into a `:kind` via a **path→kind config table** (data, not
   code — the lab tunes it without touching the engine):

```edn
{:rules
 [{:match "content/**/*.md"   :kind :page}     ; Hugo content (eksperymenty, datasety, benchmarks, recepty, ...)
  {:match "cards/**/*.{md,yaml}" :kind :card}  ; dataset / benchmark / model cards
  {:match "**/eksperymenty*.jsonl" :kind :log} ; experiment log rows
  {:match "configs/**/*.{yaml,toml}" :kind :config}
  {:match "src/**/*"          :kind :code}
  {:match "**/*.{safetensors,gguf,bin}" :kind :data}]} ; reference only, no body
```

## 2. Per-kind extractors (deterministic first)

The rule: parse what the source already structures; call the LLM only for the
gaps. For a well-formed artifact repo, Tier A invokes the LLM almost never.

| kind | source example | extractor | LLM? |
|---|---|---|---|
| `page` | `content/eksperymenty/*.md` | read frontmatter; split body by H2/H3 into sections; section → node or observation; map Hugo section → MOC + node type | no |
| `card` | dataset / benchmark / model cards | map structured fields → typed node (name, license, lineage, decontamination status, metrics) | no |
| `log` | `eksperymenty*.jsonl` | each row → one `experiment` node (hypothesis, setup, result, decision, cost) | no |
| `config` | `configs/*.yaml`, recipes | parse → `recipe` node + params; link to the run that used it | no |
| `code` | `src/**` | path + docstring/header → stub node | rarely |
| `data` | weights, large blobs | reference only (path + sha); no body | no |

Start with `log`: their experiment log already encodes
hypothesis/setup/result/decision, so it yields the most graph for the least work
and exercises the full path end-to-end.

## 3. Node materialization

Each extracted unit becomes a node (data-contracts.md §2). Key fields at this stage:

- `id`: ULID, minted once per logical entity and reused on every re-ingest
  (resolved via the alias table, see §5 — do not mint a new id for an entity that
  already exists).
- `provenance`: pin to the commit. Format:
  `git:slayerlabs/<repo>@<sha>:<path>#<section>`. The sha makes it exactly
  reproducible — non-negotiable for this lab's lineage culture.
- `status`: `current` on first sight.

## 4. Linking (three paths)

1. **Explicit** — wikilinks / relative md links in the source resolve to node ids.
2. **Structural** — an experiment row that names a dataset or a recipe file →
   `uses-dataset` / `uses-recipe` edges, resolved through the alias table.
3. **Cross-kind** — card ↔ log ↔ config tied by a shared identifier (mix name,
   run id). This is where the multi-hop value comes from.

## 5. Entity resolution & the alias table

Cards define canonical names (`style-sft-1.6k` is a card, not a guess), so seed
the `aliases` table from card titles/ids first. Resolution for an incoming
mention:

- exact alias hit → attach to that node id;
- else embedding cosine vs existing node centroids:
  - `>= tau_high` → attach;
  - `<= tau_low` → mint a new node;
  - in between → review queue.

`tau_high` / `tau_low` have no good defaults — tune them on a real export (same
discipline as a scoring pre-filter). Document the chosen values and the eval set.

## 6. Incremental sync

- On each run, `changed(since=last_sha)` = `git diff last_sha..HEAD --name-status`.
- Re-parse only changed files; upsert nodes by deterministic id; `content-hash`
  skips no-op edits.
- For `log` kinds, new rows **append** observations rather than overwrite.
- Deleted file → set the node `status: archived`; never hard-delete (lineage).
- Trigger from CI (GitHub Action on push to a source repo) → run ingest → commit
  to the KB note store → rebuild the Hugo site. The KB stays live with the repo.

## 7. How Discord plugs in later (proving the seam)

`DiscordAdapter` emits `RawItem` with `:kind :message`. It takes the Tier B path:
heavy heuristics (noise filter, simhash near-dedup, thread segmentation, language
detect), then the small local LLM for extraction, then the **same resolver** —
attaching observations to the anchor nodes the repo already created, via the
**same alias table** the cards seeded. That is the architectural payoff of
repo-first: without the repo's canonical vocabulary, Discord resolution drifts.
