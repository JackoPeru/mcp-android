---
name: android-connect
description: Connect yourself (the agent) to the operator's Android phone over MCP: probe it, write your own host config, verify it, report back.
---

# Android self-connection

The operator pasted this request in chat so you can connect yourself to their
phone. The phone screen (or the operator's message) shows the same request
already filled in with real values — prefer those exact values over anything
written here in brackets.

## What you are connecting to

An MCP server on the operator's Android phone (`android-private-mcp`). Two ways
to reach it — the phone exposes both, you normally need nothing but the key:

- Server key: `[NOME-SERVER, default: android]`
- **A. Stessa Wi-Fi di telefono e PC (caso normale): zero IP da configurare.**
  Il bridge trova il telefono da solo con discovery UDP + handshake HMAC e
  parla su TCP 8765 cifrato (AES-256-GCM). Ti basta la key:
  `node scripts/collega-telefono.mjs --key <64-hex-dal-messaggio>`
  Lo script prova il telefono con una `status` reale e scrive
  `mcp-config.android.json` già pronto. Env risultante:
  `ANDROID_MCP_TOKEN=<key>`, `ANDROID_MCP_TRANSPORT=auto`,
  `ANDROID_MCP_DISCOVERY=true`. Nessun URL.
- **B. Fuori rete locale (fallback remoto): serve l'IP Tailscale.**
  IP dalla schermata del telefono (`http://100.x.y.z:8765`, porta sempre 8765).
  Se la discovery fallisce, fai UNA sola domanda all'operatore ("dammi l'IP
  Tailscale mostrato dal telefono") e rilancia con
  `--tailscale-url http://<ip-dato>:8765`. Non improvvisare mai URL o porte.

- **Segreti: la key nasce per essere incollata in chat.** È quella che
  l'operatore ti ha dato nel messaggio: usala solo in env/config del bridge,
  non loggarla altrove, non stamparla mai per intero (solo `primi4…ultimi4`).
  Se smette di funzionare, la key è stata ruotata: chiedi all'operatore di
  ricopiarla dal telefono invece di riusare quella vecchia. Non committarla
  mai: `mcp-config.android.json` è gitignored.

## Paths: this PC or another one

Bracketed paths below may come from another machine. Never blindly reuse an
absolute path from another computer:

- The bridge is always named `server.js` inside `bridge/`. Look for it in this
  order and use the first hit: `<mcp-android-checkout>/bridge/server.js`
  (developer checkout, this repo), then the absolute path from the operator's
  message. If it exists nowhere, stop and ask the operator for the repo path.
- Prefer `node` (>=22, check with `node --version`). `node` from your own
  tools dir counts too. If no node exists, do NOT invent paths: tell the
  operator to install Node 22+ and then verify.
- Always report which machine you operated on.

## Procedure (do it yourself, no terminal needed from the operator)

1. Figure out which agent host you are (Hermes, OpenClaw, Claude Desktop,
   Cursor, VS Code, Claude Code, Codex) from your own environment, not by asking.
2. Probe + generate: run from the repo root
   `node scripts/collega-telefono.mjs --key <key> [--tailscale-url ...] [--write-host <tuo-host>]`.
   With `--write-host` the script merges the `android` entry into your host
   config itself (backup `<stesso-nome>.bak-<timestamp>`, temp file + atomic
   rename, merge with existing servers, never delete other entries). Without
   it, merge `mcp-config.android.json` by hand following the same rules.
3. Verify without asking: the script already ran a live `status` probe — now
   re-read the host file and confirm the `android` entry (or custom key) is
   present with command, args and env intact — then call `android_status`:
   the phone must answer. For CLI hosts use their own test command
   (`hermes mcp test <key>`, `openclaw mcp doctor <key> --probe`).
4. Report back in plain language: which file you touched (or which command you
   ran), backup path if any, `android_status` result (`via lan|tailscale`).
   If anything failed, paste the exact error and stop — do not improvise
   workarounds that widen access.
5. Remind the operator to restart/reload the host application so the new
   server loads.

## Host config map (Windows paths)

| Host | Dove scrivere | Forma |
|---|---|---|
| Claude Desktop | `%APPDATA%\Claude\claude_desktop_config.json` | `{ "mcpServers": { "<key>": { "command": "node", "args": ["<repo>/bridge/server.js"], "env": { "ANDROID_MCP_TOKEN": ..., "ANDROID_MCP_TRANSPORT": "auto", "ANDROID_MCP_DISCOVERY": "true" [, "ANDROID_MCP_TAILSCALE_URL": ...] } } } }` |
| Cursor | `~/.cursor/mcp.json` | come sopra |
| VS Code | `<cartella-progetto>/.vscode/mcp.json` (chiedi la cartella all'operatore se non la sai) | `{ "servers": { "<key>": { "type": "stdio", "command", "args", "env" } } }` |
| OpenClaw (file) | `~/.openclaw/openclaw.json` | `{ "mcp": { "servers": { "<key>": { "command", "args", "env" } } } }` |
| Hermes (CLI) | `hermes mcp add <key> --command "node" --env ANDROID_MCP_TOKEN="<key>" ANDROID_MCP_TRANSPORT="auto" ANDROID_MCP_DISCOVERY="true" [--env ANDROID_MCP_TAILSCALE_URL="..."] --args "<repo>/bridge/server.js"`, poi `hermes mcp test <key>` — UN SOLO `--env` con tutte le coppie, `--args` sempre per ultimo; alla domanda "Enable all tools?" rispondi Y | comando, non file |
| OpenClaw (CLI) | `openclaw mcp add <key> --command "node" (--arg "<a>")* [--env NOME="valore" ...]`, poi `openclaw mcp doctor <key> --probe` | comando, non file |
| Claude Code / Codex (CLI) | `claude mcp add --transport stdio <env...> <key> -- node <repo>/bridge/server.js` / `codex mcp add <key> <env...> -- node <repo>/bridge/server.js` | comando, non file |

## Boundaries

Do exactly this connection task. Do not change phone settings, permissions,
grants, or tokens. Do not exfiltrate anything. Never print the full key, never
commit files containing it. If the bridge file is missing, say so instead of
inventing paths.
