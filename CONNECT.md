# Connect to the Prophet MCP server

Prophet exposes the Slayer knowledge base over MCP — five **read** tools: `search`
(hybrid, cross-lingual: English queries retrieve Polish-titled nodes), `get_node`,
`traverse`, `neighbors`, `whats_new`. Every result is provenance-pinned (a
`source_url` to a commit-pinned GitHub blob). Read-only — no write tools (ADR-008).

Each `search` hit also carries a `snippet` (additive, non-breaking field): on a
lexical (FTS) match it is the matched span with the term delimited by `[ … ]`; on
a hit surfaced only by the alias or vector lane (no lexical span) it falls back to
the head of the node's indexed text, undelimited. Treat it as a preview that tells
you *why/where* a node matched before you `get_node` it.

Two ways to connect:

- **Hosted HTTP — live, open (ADR-015).** A public `https://prophet.aszc.dev/mcp`
  endpoint, FTS-only, no token. Jump to [Hosted HTTP](#hosted-http-live-open). This is
  the zero-setup path.
- **Local stdio — demo/preview (ADR-013).** The section below; adds the vector lane via
  a local omlx embedder.

## Local stdio (demo/preview)

Prereqs: the JVM/Clojure toolchain and a built corpus (see
[`docs/quickstart.md`](docs/quickstart.md)), plus a local omlx embedder for hybrid
search. Register the stdio server with Claude Code (user scope = every project);
replace `/path/to/prophet` and the omlx key:

```sh
claude mcp add slayer-kb -s user \
  --env SLAYER_EMBED_URL=http://127.0.0.1:10240 \
  --env SLAYER_EMBED_API_KEY=<omlx-key> \
  --env SLAYER_EMBED_MODEL=Qwen3-Embedding-0.6B-8bit \
  -- /bin/sh -c 'cd /path/to/prophet && exec "$(command -v clojure)" -M:run serve-mcp'
```

Verify: `claude mcp get slayer-kb` → `Status: ✔ Connected`.

- The `cd` is required — the server resolves `deps.edn` and `kb.db` from the project
  dir, and a user-scope server may launch from any cwd.
- Without the omlx env the server still runs, but the vector lane is inert (search
  falls back to FTS + alias + graph).
- Any MCP client that launches a local stdio command can run the same
  `/bin/sh -c '…'` line. This build reads config via `claude mcp …`, not
  `claude_desktop_config.json`.

---

# Hosted HTTP (live, open)

> The hosted MVP is live at `https://prophet.aszc.dev/mcp` — public, read-only,
> **open** (no token, no Origin allowlist; ADR-015). It is **FTS-only** (no embedder):
> retrieval runs on FTS + exact-alias + graph; the vector lane is Phase 2. The
> `Authorization: Bearer` lines below are shown only for when a deployment enables
> `MCP_AUTH_TOKEN` — the hosted MVP needs none.

## Claude Code (CLI)

```sh
claude mcp add --transport http slayer-kb https://prophet.aszc.dev/mcp
# if a deployment enables a token:
claude mcp add --transport http slayer-kb https://prophet.aszc.dev/mcp --header "Authorization: Bearer <TOKEN>"
```

Or as project config in `.mcp.json`:

```json
{ "mcpServers": { "slayer-kb": { "type": "http", "url": "https://prophet.aszc.dev/mcp" } } }
```

## Claude Desktop

Settings → Connectors → **Add custom connector** → paste `https://prophet.aszc.dev/mcp`.
The connection is opened from Anthropic's cloud; the hosted MVP is public and sets no
Origin allowlist, so it connects without extra configuration.

## Codex

In `~/.codex/config.toml`:

```toml
[features]
experimental_use_rmcp_client = true

[mcp_servers.slayer-kb]
url = "https://prophet.aszc.dev/mcp"
```

## Cursor

In `~/.cursor/mcp.json`:

```json
{ "mcpServers": { "slayer-kb": { "url": "https://prophet.aszc.dev/mcp" } } }
```

## API connector (programmatic)

In a Messages request, attach the server (open — no `authorization_token`):

```json
"mcp_servers": [
  { "type": "url", "url": "https://prophet.aszc.dev/mcp", "name": "slayer-kb" }
]
```
