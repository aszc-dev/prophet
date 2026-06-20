# Connect to the Prophet MCP server

Prophet exposes the Slayer knowledge base over **MCP (Streamable HTTP)** at a
single endpoint: `https://<DOMAIN>/mcp` (health: `GET https://<DOMAIN>/health`).

**What you get:** five **read** tools — `search` (hybrid, cross-lingual: English
queries retrieve Polish-titled nodes), `get_node`, `traverse`, `neighbors`,
`whats_new`. Every result is provenance-pinned (a `source_url` to a commit-pinned
GitHub blob). It is a read-only preview — there are no write tools (ADR-008).

Replace `<DOMAIN>` with the deployment host. The public preview is **open** (no
token). If a deployment sets `MCP_AUTH_TOKEN`, add the `Authorization: Bearer
<TOKEN>` header as shown in the optional auth lines below.

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
that origin (the public preview leaves the allowlist unset).

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
