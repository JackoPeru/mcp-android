#!/usr/bin/env node
// Auto-configurazione del collegamento agente <-> telefono Android.
//
// Uso (lo lancia l'agente, l'utente fornisce solo la key):
//   node scripts/collega-telefono.mjs --key <64 caratteri hex>
//   node scripts/collega-telefono.mjs --key <hex> --tailscale-url http://100.x.y.z:8765
//   node scripts/collega-telefono.mjs --check   (riverifica la config esistente)
//
// La key non viene MAI stampata, loggata o committata: nei messaggi compare
// solo mascherata (primi 4 + ultimi 4 caratteri). Il file generato
// (default: mcp-config.android.json) e' in .gitignore.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { AndroidClient, readConfig } from '../bridge/client.js';

export const KEY_RE = /^[a-f0-9]{64}$/;
export const DEFAULT_OUT = 'mcp-config.android.json';

export function normalizeKey(raw) {
  return String(raw ?? '').trim().toLowerCase();
}

export function validateKey(raw) {
  const key = normalizeKey(raw);
  if (!KEY_RE.test(key)) {
    throw new Error(
      'Key non valida: servono esattamente 64 caratteri esadecimali (0-9, a-f), ' +
      'quelli mostrati dalla schermata token del telefono.',
    );
  }
  return key;
}

export function maskKey(key) {
  const k = String(key ?? '');
  if (k.length < 8) return '****';
  return `${k.slice(0, 4)}…${k.slice(-4)}`;
}

function asBridgeEnv({ key, tailscaleUrl, lanUrl }) {
  // Stessi default del bridge: transport auto + discovery LAN attiva.
  // Sulla stessa Wi-Fi basta la key, nessun IP da configurare.
  return {
    ANDROID_MCP_TOKEN: key,
    ANDROID_MCP_TRANSPORT: 'auto',
    ANDROID_MCP_DISCOVERY: 'true',
    ...(tailscaleUrl ? { ANDROID_MCP_TAILSCALE_URL: tailscaleUrl } : {}),
    ...(lanUrl ? { ANDROID_MCP_LAN_URL: lanUrl } : {}),
  };
}

export function buildClientConfig({ key, tailscaleUrl, lanUrl, bridgePath }) {
  return {
    mcpServers: {
      android: {
        command: 'node',
        args: [bridgePath],
        env: {
          ANDROID_MCP_TOKEN: validateKey(key),
          ANDROID_MCP_TRANSPORT: 'auto',
          ANDROID_MCP_DISCOVERY: 'true',
          ...(tailscaleUrl ? { ANDROID_MCP_TAILSCALE_URL: tailscaleUrl } : {}),
          ...(lanUrl ? { ANDROID_MCP_LAN_URL: lanUrl } : {}),
        },
      },
    },
  };
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`Argomento non riconosciuto: ${a}`);
    const name = a.slice(2);
    if (name === 'check' || name === 'dry-run') { out[name] = true; continue; }
    const value = argv[++i];
    if (value === undefined || value.startsWith('--')) throw new Error(`Opzione --${name} senza valore.`);
    out[name] = value;
  }
  return out;
}

function readKey(args) {
  // La key non deve mai finire in shell history condivise: preferisci
  // ANDROID_MCP_TOKEN d'ambiente; --key e' il fallback esplicito.
  const raw = args.key ?? process.env.ANDROID_MCP_TOKEN ?? '';
  if (!raw) {
    throw new Error(
      'Key mancante: passa --key <64 hex> oppure esporta ANDROID_MCP_TOKEN. ' +
        'La trovi nel telefono: app MCP Android > schermata token > Copia negli appunti.',
    );
  }
  return validateKey(raw);
}

async function probeLive(env, { timeoutMs = 15000 } = {}) {
  // Verifica reale: discovery LAN (o URL configurati) + handshake + status.
  const config = readConfig({ ...process.env, ...env });
  const client = new AndroidClient({ ...config, timeoutMs, probeTimeoutMs: 3000 });
  const status = await client.call('status', {});
  return status;
}

function writeConfigFile(outPath, config) {
  const dir = path.dirname(outPath);
  if (dir !== '.') fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });
}

export async function main(argv = process.argv.slice(2), cwd = process.cwd()) {
  const args = parseArgs(argv);
  const bridgeAbs = path.join(
    path.dirname(fileURLToPath(import.meta.url)),
    '..',
    'bridge',
    'server.js',
  );
  const outPath = path.resolve(cwd, args.out ?? args.output ?? DEFAULT_OUT);

  const key = readKey(args);

  if (args.check) {
    // Riverifica senza riscrivere: usa la config su disco se presente.
    let env = asBridgeEnv({ key });
    if (fs.existsSync(outPath)) {
      const disk = JSON.parse(fs.readFileSync(outPath, 'utf8'));
      const diskEnv = disk?.mcpServers?.android?.env ?? {};
      if (diskEnv.ANDROID_MCP_TOKEN && normalizeKey(diskEnv.ANDROID_MCP_TOKEN) !== key) {
        throw new Error(
          `La key su disco (${outPath}) non coincide con quella fornita (${maskKey(key)}). ` +
            'Rilancia senza --check per aggiornarla.',
        );
      }
      env = { ...diskEnv, ANDROID_MCP_TOKEN: key };
    }
    const status = await probeLive(env);
    return { ok: true, key: maskKey(key), via: status?.preferredTransport ?? 'unknown', status };
  }

  const config = buildClientConfig({
    key,
    tailscaleUrl: args['tailscale-url'],
    lanUrl: args['lan-url'],
    bridgePath: bridgeAbs,
  });

  if (!args['dry-run']) {
    const status = await probeLive(config.mcpServers.android.env);
    writeConfigFile(outPath, config);
    return {
      ok: true,
      key: maskKey(key),
      out: outPath,
      via: status?.preferredTransport ?? 'unknown',
    };
  }
  return { ok: true, dryRun: true, key: maskKey(key), out: outPath };
}

const invokedAsScript =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedAsScript) {
  main().then(
    result => {
      console.log(`OK: telefono raggiungibile via ${result.via ?? 'verifica saltata (dry-run)'}.`);
      if (result.out) console.log(`Config scritta in: ${result.out}`);
      console.log(`Key usata: ${result.key} (mai mostrata per intero, mai committata).`);
      console.log('Prossimo passo: punta il tuo client MCP a questo file e chiama android_status.');
    },
    error => {
      console.error(`ERRORE: ${error.message}`);
      if (String(error.message).includes('unreachable') || String(error.message).includes('endpoint')) {
        console.error(
          'Suggerimento: telefono e PC devono stare sulla stessa Wi-Fi con "Avvia controllo remoto" attivo, ' +
            'oppure passa --tailscale-url http://100.x.y.z:8765 (IP mostrato dal telefono).',
        );
      }
      process.exitCode = 1;
    },
  );
}
