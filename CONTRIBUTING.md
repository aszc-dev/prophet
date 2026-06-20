# Contributing

- **Build/test:** `bb test` (JVM). Index-touching tasks shell to `clojure -M:run`
  (ADR-006); see [`docs/quickstart.md`](docs/quickstart.md).
- **Lint/format:** `bb lint` (clj-kondo) and `bb fmt:check` (cljfmt) must pass.
  Run `pre-commit install` to enforce these plus a secrets scan on commit.
- **Comments:** behaviour lives in docstrings. Inline comments are for non-obvious
  rationale ("why"), not restating what the code does.
- **Provenance invariant:** every node and synthesized claim links to its
  source(s) via an exact ref. No provenance → do not write it. `kb/` is
  append-only; never overwrite or hard-delete (supersede with a pointer).
- All code, comments, commits, and docs are in English.
