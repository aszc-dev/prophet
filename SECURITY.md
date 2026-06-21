# Security Policy

## Reporting a vulnerability

Please report security issues privately to **kontakt@aszczepanski.pl**. Do not
open a public issue for a vulnerability. Include a description, reproduction
steps, and the affected commit. Expect an initial response within a few business
days.

## Scope notes

- **Public exposure.** Prophet is intended to serve a public MCP endpoint and a
  static web build. The MCP surface is read-only (five tools; no write tools,
  ADR-008). When the deployment hardening lands, the server runs behind a reverse
  proxy with rate limiting and request-size limits; protocol output stays on
  stdout, logs on stderr.
- **Corpus contents.** The `kb/` note store can contain data lineage and source
  URLs. The posture is code-only (ADR-014): `kb/` is built on the deploy host from
  its source repo and is not redistributed by this repo.
- **Secrets.** No credentials belong in the repo. A `gitleaks` pre-commit hook
  scans staged changes; embedder endpoints and keys are read from the
  environment (`SLAYER_EMBED_*`), never committed.
