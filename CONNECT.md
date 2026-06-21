# Connect to the Prophet MCP server

Prophet exposes the Slayer knowledge base over MCP — five **read** tools: `search`
(hybrid, cross-lingual: English queries retrieve Polish-titled nodes), `get_node`,
`traverse`, `neighbors`, `whats_new`. Every result is provenance-pinned (a
`source_url` to a commit-pinned GitHub blob). Read-only — no write tools (ADR-008).

Two ways to connect:

- **Local stdio — current demo/preview (ADR-013).** The section below.
- **Hosted HTTP — parked.** A public `https://<DOMAIN>/mcp` endpoint that returns
  once Slayer hosts the embedder. The per-client HTTP configs follow it, ready for
  when it is live.

## Local stdio (current)

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

# Hosted HTTP (parked — returns with the public deployment)

> The endpoints below use `https://<DOMAIN>/mcp`. They apply to the **parked** hosted
> deployment (ADR-013); there is **no public preview** during the local demo phase.
> Replace `<DOMAIN>` with the deployment host once it exists. If a deployment sets
> `MCP_AUTH_TOKEN`, add the `Authorization: Bearer <TOKEN>` header as shown.

## Claude Code (CLI)

```sh
claude mcp add --transport http prophet https://<DOMAIN>/mcp
# with a token:
claude mcp add --transport http prophet https://<DOMAIN>/mcp --header "Authorization: Bearer <TOKEN>"
```

Or as project config in `.mcp.json`:

```json
{ "mcpServers": { "prophet": { "type": "http", "url": "https://<DOMAIN>/mcp" } } }
```

## Claude Desktop

Settings → Connectors → **Add custom connector** → paste `https://<DOMAIN>/mcp`.
The connection is opened from Anthropic's cloud, so the endpoint must be public;
if the deployment sets an Origin allowlist (`MCP_ALLOWED_ORIGINS`), it must admit
that origin.

## Codex

In `~/.codex/config.toml`:

```toml
[features]
experimental_use_rmcp_client = true

[mcp_servers.prophet]
url = "https://<DOMAIN>/mcp"
```

For an authed server, log in once: `codex mcp login prophet`.

## Cursor

In `~/.cursor/mcp.json`:

```json
{ "mcpServers": { "prophet": { "url": "https://<DOMAIN>/mcp" } } }
```

With a token, add headers:

```json
{ "mcpServers": { "prophet": { "url": "https://<DOMAIN>/mcp",
  "headers": { "Authorization": "Bearer <TOKEN>" } } } }
```

## API connector (programmatic)

In a Messages request, attach the server:

```json
"mcp_servers": [
  { "type": "url", "url": "https://<DOMAIN>/mcp", "name": "prophet",
    "authorization_token": "<TOKEN>" }
]
```

Omit `authorization_token` when the server is open.
