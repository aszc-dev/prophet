# Decisions (ADR)

Settled decisions, with rationale and consequences, so they are not re-litigated.
Format per record: Context / Decision / Consequences / Risks.

---

## ADR-001 — md + YAML for storage; org-roam as a personal client

**Context.** The shared KB serves a ~162-person, web-first community and LLM
clients. One power user (the maintainer) lives in Emacs/org-roam.

**Decision.** Storage and interchange format is **md + YAML frontmatter**.
org-roam is a *personal authoring/reading client*, not the storage format,
bridged via the existing org→md toolchain (ox-hugo / pandoc), sharing the same
stable `id`.

**Consequences.** The whole md ecosystem (RAG chunkers, MCP knowledge tools, Hugo,
LLM priors) works out of the box. The maintainer keeps org ergonomics
(capture, refile, property inheritance, babel) locally and flattens on export.

**Risks.** Round-trip fidelity org↔md at the property level; keep frontmatter
flat enough to survive both directions.

---

## ADR-002 — Append-only + supersede; no biological decay

**Context.** Compared against `greg00ry/the-brain`, whose headline feature is
strength-based decay/pruning of memories.

**Decision.** Memory is **append-only**; replaced facts are marked
`superseded` with a pointer; removed sources become `archived`. Nothing is
pruned or silently forgotten.

**Consequences.** Full audit trail and lineage — which is the lab's product, not
noise. Aligns with "lineage i disclosure". Storage grows monotonically (cheap for
text).

**Risks.** Synthesis must actively down-weight stale info (recency) so the
"current state" stays readable despite never deleting history.

---

## ADR-003 — Provenance is mandatory

**Context.** The lab's culture: "held-out albo nic", published a contaminated mix
with full disclosure rather than hiding it. A KB without citations will not be
trusted here.

**Decision.** Every node and every synthesized claim carries >=1 exact
provenance ref. No provenance → not written. Synthesis emits no uncited claim
(hard guardrail, checked in CI).

**Consequences.** Trust; reproducibility; the engine encodes the community's norm
instead of fighting it.

**Risks.** Extraction must always carry the ref through; a claim that loses its
ref is dropped, which can lose signal — acceptable trade.

---

## ADR-004 — Hybrid retrieval (FTS + vector + graph), not pure vector

**Context.** Corpus spans chat ↔ repo ↔ docs; many queries are relational /
multi-hop. Pure vector RAG is competitive only on flat semantic lookups; graph
traversal wins materially on multi-hop, at higher operational cost.

**Decision.** Combine FTS5 (BM25) + sqlite-vec (cosine) + typed-graph traversal
behind one `search`.

**Consequences.** Multi-hop questions (decision → experiment → dataset → license)
are answerable with traceable paths.

**Risks.** More moving parts than a single vector index. Mitigated by keeping all
three in one SQLite file.

---

## ADR-005 — Deterministic-first ingest; local small LLM only for gaps

**Context.** Limited time/compute; no appetite for large experiments. Tier A is
already structured.

**Decision.** Parse what the source structures; invoke a small **local MLX** model
only to fill genuine gaps (freeform extraction, summarization). Tier A should call
the LLM almost never.

**Consequences.** v0–v0.5 need no GPU/training; cheap, fast, offline.

**Risks.** LLM JSON reliability on Tier B → strict schema validation, one retry,
then review queue.

---

## ADR-006 — SQLite + FTS5 + sqlite-vec as the index

**Context.** Want a single-file, local, rebuildable index with lexical + semantic
+ graph in one place; Clojure/Babashka stack.

**Decision.** SQLite with FTS5 and the `sqlite-vec` extension. Index is derived,
never authoritative.

**Consequences.** Zero infra; trivial backup; rebuildable from md.

**Risks.** **Loading the `sqlite-vec` extension from Clojure/Babashka.** Validate
early: JVM via `next.jdbc` + sqlite-jdbc with `enable_load_extension`; if Babashka
can't load it cleanly, run the vector path in a small JVM Clojure service and keep
BB for the rest. Decide this in a spike before committing v0's index design.

**Spike result (2026-06-18) — RESOLVED, Plan B confirmed.** Tested on macOS
arm64 (sqlite-vec `vec0.dylib` v0.1.9):

- ✅ **JVM Clojure path works fully.** `clojure` CLI + `org.xerial/sqlite-jdbc
  3.49.1.0`, opening the connection with
  `SQLiteConfig.enableLoadExtension(true)` then `select load_extension('vec0')`.
  Verified: `vec_version() = v0.1.9`; `vec0` virtual table create + `match`/KNN
  ordering; FTS5 (bundled in sqlite-jdbc) and `vec0` coexist in one **file-backed**
  DB and survive close/reopen. This is the rebuildable single-file index from the
  decision above.
- ❌ **Babashka cannot load sqlite-vec — both BB sub-paths fail:**
  - sqlite-jdbc as a BB JVM dep → `Unable to resolve classname:
    org.sqlite.SQLiteConfig` (classes absent from BB's GraalVM native image; the
    JNI-backed driver isn't loadable this way).
  - `org.babashka/go-sqlite3` pod (0.2.4) → `load_extension(...)` returns **`not
    authorized`** (mattn/go-sqlite3 ships with extension loading disabled).

**Consequent stack decision.** The **`index/*` layer runs on the JVM** (Clojure +
sqlite-jdbc). Babashka stays the task runner / CLI glue and hosts the source
adapters and pure transforms (`extract/*`, `resolve/*` are pure fns over data,
runnable from either runtime). BB tasks that touch the index shell out to a
`clojure -M` entrypoint. The MCP server is JVM Clojure regardless. `vec0.dylib`
is **arch-specific** — vendor per-arch (arm64 confirmed; x86_64 untested) and
resolve the path at runtime.

---

## ADR-007 — Clojure + Babashka stack

**Context.** Maintainer's primary expertise; scriptable; pure-fn friendly for
transforms.

**Decision.** Pipeline and tooling in Clojure + Babashka. Pure functions for
transforms; IO (git, http, sqlite, fs) isolated at the edges. MCP server in
Clojure.

**Consequences.** Fast iteration for the maintainer; good fit for the
data-transform shape of the pipeline.

**Risks.** Some ecosystem pieces (sqlite-vec, MCP server libs) are less mature in
Clojure — spike each before depending on it.

---

## ADR-008 — Governance: open reads, gated writes, reviewed new nodes

**Context.** Shared graph for a large community; auto-writing invites junk.

**Decision.** MCP reads are open. Writes are provenance-gated. New nodes/links go
to a **review queue**, not the live graph. Observations with valid provenance may
auto-append.

**Consequences.** The canonical graph stays clean; contributions are still cheap.

**Risks.** Review becomes a bottleneck → keep the queue lightweight (PR-style),
and let well-formed provenance-bearing observations through automatically.

---

## ADR-009 — Embedding dimension pinned at 1024; model swap via MRL truncation

**Context.** The `vec_nodes` table fixes a vector dimension at DDL time
(`vec0(... embedding FLOAT[N])`). Changing `N` later means dropping and
rebuilding the vector table against a re-embedded corpus — a real migration we
want to avoid as the model evolves (0.6B → 4B/8B as the corpus and quality bar
grow).

**Decision.** Pin **`N = 1024`**. v0 default embedder is
**`mlx-community/Qwen3-Embedding-0.6B-8bit`** (1024 native dims; 8-bit MLX quant —
negligible quality loss at this size, lower memory), served over an
OpenAI-compatible `/v1/embeddings` endpoint. The whole Qwen3-Embedding family
supports **Matryoshka (MRL)**, so a later swap to 4B (2560) / 8B (4096) keeps the
schema: request `dimensions: 1024` (or truncate client-side) for a minimal quality
drop and no migration. Stub vs real embeddings is therefore a **data** decision,
never a schema one.

**Serving (operational) — SUPERSEDED (see ADR-010, then ADR-013).** The original
serving guidance (omlx for dev) is kept here as history. The runtime later moved to
**TEI** (ADR-010), then back to **local omlx** for the demo phase with TEI/hosted
parked (ADR-013, current). The dimension-pin decision above still stands. The endpoint was
**omlx** (`omlx serve`, model symlinked under `~/.omlx/models/<org>/<name>/`),
**not** `mlx_lm.server` — the latter exposes only completions, not
`/v1/embeddings`. Two gotchas, both load-bearing:

- `SLAYER_EMBED_URL` must use **`127.0.0.1`**, never `localhost`. The JVM
  HttpClient resolves `localhost` to IPv6 `::1`, but the MLX servers bind IPv4 only
  → `ConnectException`.
- omlx requires a bearer token — set `SLAYER_EMBED_API_KEY`.

The optional grounded-definition gap-fill (glossary) uses the **same conventions**
on its own chat endpoint: `SLAYER_CHAT_URL` (same `127.0.0.1` rule), `SLAYER_CHAT_MODEL`
(default `mlx-community/Qwen3-8B-4bit`), `SLAYER_CHAT_API_KEY`.

**Inert-stub rule.** When the embedding endpoint is absent, v0 may write
placeholder vectors **only** to exercise the write path. Stub vectors are
**inert**: the hybrid blend weights `vec` at 0 and they never enter ranking. A
deterministic hash vector has no cosine meaning; blending it poisons results.
Embeddings are off the v0 critical path (FTS + exact + graph carry the acceptance
queries); real `vec` becomes load-bearing at Tier B (Discord fuzzy resolver).

**Consequences.** Schema survives every planned model change; v0 ships whether or
not the endpoint is up; the index DDL can hardcode `FLOAT[1024]` now.

**Risks.** Server-side MRL can be finicky — e.g. vLLM returns the full dimension
by default and 400s on a `dimensions` request unless launched with
`--hf-overrides '{"is_matryoshka": true, ...}'`. Verify the chosen MLX server
honors `dimensions: 1024` before pinning a larger model; until then keep 0.6B,
whose native width already is 1024.

## ADR-010 — TEI is the embedding runtime (supersedes ADR-009's serving section)

**Status: SUPERSEDED for the demo phase by ADR-013.** TEI and the containerized path
are *parked* (the future hosted phase); the current demo/preview runtime is local
omlx. The decision below is retained as history.

**Context.** Qwen3-Embedding vectors are **not interchangeable across runtimes**:
the same text embedded by different runtimes (omlx/MLX vs TEI vs vLLM) can land at
cosine < 0.2. Document vectors and query vectors must come from one runtime + one
model revision, or retrieval silently breaks. ADR-009 picked omlx for local dev;
that does not generalize to CI and the deploy host.

**Decision.** **HuggingFace Text Embeddings Inference (TEI)** is the one embedding
runtime everywhere — dev, CI, and prod. Pin the image
(`ghcr.io/huggingface/text-embeddings-inference:cpu-1.8`) and the model
(`Qwen/Qwen3-Embedding-0.6B`); these pins are the dev=CI=prod contract. The
dimension stays 1024 (ADR-009). A developer may use any endpoint locally, but
vectors that enter `kb.db` must come from the pinned TEI.

**Operational.**
- TEI exposes the OpenAI-compatible `/v1/embeddings`; the client posts there
  (`SLAYER_EMBED_URL` + `SLAYER_EMBED_MODEL`).
- **Do not send `:dimensions` at native width** — TEI (and vLLM) 400 on it. The
  client omits the param at 1024 and sends it only for genuine MRL truncation
  (`embed/request-body`).
- TEI L2-normalizes server-side; `vec_nodes` uses sqlite-vec's default L2 metric,
  so L2-rank == cosine-rank on unit vectors. No client-side normalization.
- The `127.0.0.1`-not-`localhost` IPv6 gotcha (ADR-009) applies to host-local
  endpoints; across the container network the service name resolves to IPv4.
- This model has no ONNX export, so TEI uses its Candle CPU backend. cpu-1.7's
  Candle backend aborts at warmup on some CPUs ("Intel MKL ERROR: Parameter N …
  GEMM"); cpu-1.8 fixes it. Pass `--auto-truncate` (required when
  `--max-batch-tokens` is below the model's max input length).

**Validation (Gate B).** The omlx baseline does not carry over by assumption.
Before ratcheting `eval:gate`, re-run `eval/retrieval-gold.edn` with TEI producing
**both** document and query vectors on the host and confirm the numbers reproduce
(target r@10 ≈ 95%, EN r@10 ≈ 90%, PL 100%). Commit the TEI scorecard as the new
floor only after it passes.

## ADR-011 — MCP telemetry: append-only tool-call log feeding the gold-set eval

**Context.** We want to know which real queries retrieval serves well or poorly,
to grow `eval/retrieval-gold.edn` from real traffic instead of hand-authoring. The
server makes no LLM calls — this is retrieval/tool-span capture, not LLM
observability. At current scale a platform/collector (Langfuse, Phoenix, OTLP) is
unjustified overhead.

**Decision.** Capture every tool call at the single shared chokepoint
(`server/handle`, `tools/call`) as one append-only JSONL record. The JSONL is the
primary record (captured events — not regenerable from `kb/`); `telemetry.db` is a
**derived** mirror of it (rebuilt by `bb telemetry:gaps`, reusing the `index.*`
SQLite layer). Neither is `kb.db`, and `bb index:rebuild` never touches them.

**Properties.**
- **Inert by default.** No sink unless `PROPHET_TELEMETRY_PATH` is set; `emit!` is
  then a no-op (mirrors the embed-disabled pattern, ADR-009) and never throws.
  Writes are serialized for the multi-threaded HTTP transport.
- **`handle` stays pure-ish.** Signature unchanged; `session_id`/`transport` flow
  via dynamic vars bound per transport (stdio = one session; http = the W3C
  `traceparent` trace-id when present, else a fresh uuid).
- **OTel-mappable, not OTel-literal.** Records use SQL-friendly keys; the shape is
  a lossless superset of the OTel tool-span + retrieval-span attributes
  (`tool`→`gen_ai.tool.name`, `args`→`gen_ai.tool.call.arguments`,
  `result_ids`/`result_count`→retrieval-span document ids/count,
  `latency_ms`→span duration, `is_error`/`error_msg`→span status,
  `session_id`→trace correlation). An OTLP exporter is a pure rename, deferred.

**Gap distillation.** `bb telemetry:gaps` mirrors the JSONL into `telemetry.db` and
emits EDN candidate gold-set queries: searches that returned nothing, scored below a
floor (`PROPHET_TELEMETRY_SCORE_FLOOR`), or had no follow-up `get_node` on any
returned id within the same session — shaped for hand-labelling into the gold set.

**Open question (future work).** A successful retrieval and a poor one both return
HTTP 200, so the query log alone cannot judge retrieval quality. The implicit
follow-up `get_node` signal is a weak proxy; an explicit `feedback` tool would be
stronger but breaks the v0 read-only contract (ADR-008). Revisit with the v1.5
write/synth tools (which will make real LLM calls and justify a fuller exporter).

## ADR-013 — Demo phase: self-hosted macOS + local omlx embeddings (defer hosted inference)

**Context.** The hosted-embeddings path explored after ADR-010 did not pan out for a
shippable deployment:
- **TEI on CPU** (ADR-010) is too slow on the shared deploy VPS — embedding the
  corpus on first boot blocks startup past any acceptable window, and the Candle CPU
  backend OOMs under tight memory caps. CPU inference is out.
- A **serverless Gemini** runtime (drafted as ADR-012, **withdrawn before commit**)
  was rejected for the demo: it makes a preview depend on a third-party API we do not
  own, on a free tier whose data-use and rate limits we do not control.

We need a working demo/preview now, on owned hardware. The **local omlx** server
(MLX on Apple Silicon GPU) was the original dev embedder and **already works**: it
serves an OpenAI-compatible `/v1/embeddings`, and its `Qwen3-Embedding-0.6B-8bit`
@ 1024-d vectors are the baseline the gold set was measured against.

**Decision.** For the **demo/preview phase**, the embedding runtime is the **local
omlx** server:
- `SLAYER_EMBED_URL=http://127.0.0.1:10240`, `SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit`,
  `SLAYER_EMBED_API_KEY=<omlx bearer>`. Dimension stays **1024** (ADR-009 pin holds);
  `vec_nodes` is `FLOAT[1024]`. The client omits `:dimensions` at native width.
- The MCP server is served **over stdio** on macOS (`bb serve:mcp`, registered via
  `claude mcp add`) — no public transport for the preview.
- ADR-010's single-runtime invariant still holds: document and query vectors both
  come from omlx; vectors are not interchangeable across runtimes.

**Status of prior decisions.** ADR-010 (TEI) and the containerized deployment
(Docker, MCP-HTTP + static site) are **parked**, not removed — they are the
future *hosted* path. omlx is Apple-Silicon-only and cannot run in the Linux
container, so the container path is inherently the hosted-inference path. ADR-012
(Gemini) is **withdrawn (not adopted)**.

**Hosted inference is deferred to Slayer.** Stable online inference (a hosted GPU
embedder, or a paid embeddings API) is a resourced effort the solo maintainer is not
taking on now; the lab has the resources for it. When that lands, revisit the runtime
choice and re-run Gate B against it.

**Consequences.**
- Deployment for the demo is bare macOS + stdio — no Docker, no external API
  dependency, reproducible on the maintainer's machine.
- 1024 is the **original measured baseline**, so `eval:gate` keeps its current floor:
  Gate B is a **re-confirmation** (re-run `eval/retrieval-gold.edn` with omlx on the
  real corpus and verify the numbers hold), not a re-baseline.
- The vector index is **derived** (invariant #1), so switching back — or forward to a
  hosted runtime later — is just a full re-embed + `bb index:rebuild`, no
  canonical-store migration.

**Risks.** The demo depends on the maintainer's Mac + omlx being up; not suitable for
always-on public exposure (that waits on the deferred hosted path).

## ADR-014 — Going-public decisions: code-only repo, identity `prophet`, MIT license

The three decisions that gated the public release are settled and recorded here; the
working notes that tracked them are no longer kept in the tree.

**1. Code-only, no `kb/` corpus — DECIDED.** The repository ships code only. The
`kb/` note store holds the lab's private data lineage, so it is untracked,
gitignored, and was purged from git history; it is never redistributed here. The
corpus is rebuilt locally or on the deploy host from its public source repo
`slayerlabs/slayer` (`bb ingest:repo`), and only `visibility: public` nodes reach the
public web/MCP. Licensing of `kb/` content is a separate question, out of scope for
this code repo.

**2. One public identity — `prophet` — DECIDED.** The repo, the namespace tree
(`src/prophet/`), `deps.edn` `:main-opts`, and the MCP `serverInfo.name` are all
`prophet`. Provenance refs to the upstream `slayerlabs/slayer` are unchanged.

**3. License — MIT — DECIDED.** `LICENSE` (SPDX `MIT`) at the repo root covers the
code; it is referenced from the README. `kb/` content licensing is deferred with
decision 1.

## ADR-015 — Hosted MVP: public, open, FTS-only HTTP MCP on a self-hosted host

**Context.** ADR-013 deferred *hosted inference* and parked the whole container path,
leaving the demo at macOS + stdio. But the value a consumer wants — a shared, always-on
HTTP MCP over the live corpus — does not depend on inference. Only the embedder was the
blocker, and the vector lane is not on the critical path: FTS + exact-alias + graph
already carry retrieval (gold set r@10 ≈ 0.95), and with no `SLAYER_EMBED_URL` the RRF
fusion simply drops the inert vector lane (`index/query.clj`). The corpus is rebuilt at
boot from the **public** `slayerlabs/slayer` (entrypoint clone → ingest → glossary →
index → web), so *where* it runs is fungible — a small always-on container is enough.
The maintainer already operates a self-hosted always-on host, and `prophet.aszc.dev`
already resolves to it over HTTPS.

**Decision.** Ship a **hosted MVP now**, in the FTS-only variant:
- **Single FTS-only container**, reproducible from the repo via a committed
  `docker-compose.yml` (one service `prophet`, built from the `Dockerfile`,
  boot-rebuild from `PROPHET_SOURCE_REPO` = public `slayerlabs/slayer`). No embedder:
  `SLAYER_EMBED_URL` is unset, so the vector lane is inert by design.
- **Public and open.** The data is public and the surface is read-only (ADR-008), so the
  MVP runs with **no bearer** (`MCP_AUTH_TOKEN` unset) and **no Origin allowlist**
  (`MCP_ALLOWED_ORIGINS` unset) — minimum friction for agent consumers. Both controls
  remain available in the HTTP transport and can be flipped on if abuse appears.
- **Endpoint.** `https://prophet.aszc.dev/mcp` (MCP), `/health` (liveness), `/` (the
  static Hugo site), all from the one JVM process (`bb serve:mcp-http`).
- **Freshness.** A scheduled redeploy (~15 min) re-runs the boot pipeline against the
  latest public source, so `whats_new` reflects upstream changes. Push-to-`main`
  autodeploy (GitHub App source) ships code changes.

**Status of prior decisions.** This **un-parks** the container / MCP-HTTP + static-site
path from ADR-013, **in the FTS-only variant only**. Hosted *inference* stays deferred
to Slayer (ADR-013): turning the vector lane on — a CPU TEI sidecar (ADR-010) or a paid
embeddings API — is **Phase 2**, governed by the single-runtime vector invariant
(ADR-010) and re-validated against Gate B. The `docker-compose.yml` removed when the
path was parked is re-added here as the reproducible deploy unit.

**Consequences.**
- A live, shared HTTP MCP + public web at ~zero new infra, consumable by any MCP client
  today (`claude mcp add --transport http`).
- The index is **derived** (invariant #1), so enabling the embedder later is a re-embed
  + `bb index:rebuild` — no canonical-store migration, no schema change (`FLOAT[1024]`
  already pinned, ADR-009).

**Risks.**
- **Open endpoint = unauthenticated compute.** Reads are cheap SQLite/FTS lookups; if
  load or abuse warrants it, flip on the bearer/allowlist or add edge rate-limiting.
- **No vector recall** in the MVP — cross-lingual expansion is weaker than the hybrid
  baseline. Accepted: the gold set passes on FTS + alias + graph.
- Availability tracks the self-hosted host; not yet a hardened public SLA.
