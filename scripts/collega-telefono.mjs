#!/usr/bin/env node
// Auto-configurazione del collegamento agente <-> telefono Android.
//
// Uso (lo lancia l'agente, l'utente fornisce solo la key):
//   node scripts/collega-telefono.mjs --key <64 caratteri hex>
//   node scripts/collega-telefono.mjs --key <hex> --tailscale-url http://100.x.y.z:8765
//   node scripts/collega-telefono.mjs --key <hex> --write-host auto   (scrive anche nel config del tuo client MCP)
//   node scripts/collega-telefono.mjs --check   (riverifica la config esistente)
// Uso (per l'utente: genera il messaggio già pronto da incollare all'agente):
//   node scripts/collega-telefono.mjs --print-prompt [--key <hex>]
//
//   Stampa il prompt completo e PORTABILE (zero percorsi assoluti: l'agente
//   gira su un'altra macchina e risolve tutto da sé). Se passi --key, la key
//   compare per intero nel messaggio (serve all'agente: copialo in chat e
//   basta). Senza --key resta il segnaposto INCOLLA_QUI_LA_KEY da sostituire.
//   Non tocca rete né disco.
// Host per --write-host: auto (= l'unico config esistente), claude-desktop, cursor, vscode, openclaw.
// --server-key cambia il nome del server (default: android).
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

export const HOSTS = ['claude-desktop', 'cursor', 'vscode', 'openclaw'];

// Tabella config host (stessa di skills/android-connect/SKILL.md).
// shape: dove vive la entry del server nel JSON del client.
export function resolveHostFile(host, { APPDATA = process.env.APPDATA ?? '', HOME = process.env.HOME ?? process.env.USERPROFILE ?? '', cwd = process.cwd() } = {}) {
  switch (host) {
    case 'claude-desktop':
      return { path: path.join(APPDATA, 'Claude', 'claude_desktop_config.json'), shape: 'mcpServers' };
    case 'cursor':
      return { path: path.join(HOME, '.cursor', 'mcp.json'), shape: 'mcpServers' };
    case 'vscode':
      return { path: path.join(cwd, '.vscode', 'mcp.json'), shape: 'servers' };
    case 'openclaw':
      return { path: path.join(HOME, '.openclaw', 'openclaw.json'), shape: 'mcp.servers' };
    default:
      throw new Error(`Host non riconosciuto: ${host}. Usa uno di: ${HOSTS.join(', ')}.`);
  }
}

export function detectHostFile({ hosts = HOSTS, opts = {} } = {}) {
  const existing = hosts
    .map(h => ({ host: h, ...resolveHostFile(h, opts) }))
    .filter(x => fs.existsSync(x.path));
  if (existing.length === 1) return existing[0];
  if (existing.length === 0) {
    throw new Error(
      'Nessun config host trovato. Specifica --write-host <host> ' +
        `(${HOSTS.join(', ')}) oppure crea prima il file del tuo client MCP.`,
    );
  }
  throw new Error(
    'Trovati più config host: ' + existing.map(x => `${x.host} (${x.path})`).join(', ') +
      '. Specifica --write-host <host>.',
  );
}

function setNested(doc, shape, serverKey, entry) {
  if (shape === 'mcpServers') {
    doc.mcpServers = { ...(doc.mcpServers ?? {}), [serverKey]: entry };
  } else if (shape === 'servers') {
    doc.servers = { ...(doc.servers ?? {}), [serverKey]: { type: 'stdio', ...entry } };
  } else if (shape === 'mcp.servers') {
    doc.mcp = doc.mcp ?? {};
    doc.mcp.servers = { ...(doc.mcp.servers ?? {}), [serverKey]: entry };
  }
  return doc;
}

// Merge sicuro: backup .bak-<timestamp>, scrittura su tmp + rename atomico,
// le altre entry restano intatte. Rifiuta JSON non oggetto (mai sovrascrivere alla cieca).
export function mergeHostConfigFile(hostPath, shape, serverKey, entry) {
  let doc = {};
  let backup = null;
  if (fs.existsSync(hostPath)) {
    const raw = fs.readFileSync(hostPath, 'utf8');
    try {
      doc = JSON.parse(raw);
    } catch {
      throw new Error(`Config host illeggibile (non JSON valido): ${hostPath}. Sistemalo a mano, non lo tocco.`);
    }
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
      throw new Error(`Config host con forma inattesa: ${hostPath}. Sistemalo a mano, non lo tocco.`);
    }
    const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
    backup = `${hostPath}.bak-${stamp}`;
    fs.copyFileSync(hostPath, backup);
  } else {
    fs.mkdirSync(path.dirname(hostPath), { recursive: true });
  }
  setNested(doc, shape, serverKey, entry);
  const tmp = `${hostPath}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, JSON.stringify(doc, null, 2) + '\n', { mode: 0o600 });
  fs.renameSync(tmp, hostPath);
  return { path: hostPath, backup };
}

// Messaggio già pronto da incollare all'agente (stesso ruolo del messaggio
// generato dalla console di Desktop-AI).
// PORTABILITÀ: qui dentro ZERO percorsi assoluti — l'agente gira su UN'ALTRA
// macchina e risolve tutto da sé (nomi file, path relativi, discovery).
// key: se fornita compare per intero (l'agente ne ha bisogno in chat),
// altrimenti resta il segnaposto INCOLLA_QUI_LA_KEY.
export const KEY_PLACEHOLDER = 'INCOLLA_QUI_LA_KEY';

export function buildAgentPrompt({ key = null, serverKey = 'android' } = {}) {
  const shown = key ? validateKey(key) : KEY_PLACEHOLDER;
  return [
    `Collegati al mio telefono Android via MCP (server "${serverKey}").`,
    '',
    `KEY DEL TELEFONO (segreta: non diffonderla oltre questa chat): ${shown}`,
    '',
    'Contesto: hai una copia della repo mcp-android. Il bridge è il file server.js dentro la cartella bridge/.',
    'Trovalo in quest\u2019ordine e usa il primo che esiste, senza inventare path:',
    '1. <tua-copia-della-repo>/bridge/server.js (checkout sviluppatore);',
    '2. il percorso del bridge se te l\u2019ho dato io in questo messaggio.',
    'Esegui tutto dalla root di quella repo, sulla TUA macchina (questo messaggio non contiene percorsi assoluti).',
    '',
    'SE TELEFONO E PC SONO SULLA STESSA WI-FI (il caso normale: niente IP da configurare):',
    '1. Controlla node >= 22 (node --version); se manca node_modules nella repo, lancia `npm ci --ignore-scripts`.',
    '2. Esegui dalla root della repo:',
    `     node scripts/collega-telefono.mjs --key ${shown} --write-host auto`,
    '   Lo script trova il telefono da solo (discovery LAN + handshake), lo verifica',
    '   con una `status` reale, scrive mcp-config.android.json e fonde la entry nel',
    '   config del tuo host (backup .bak-timestamp, merge senza cancellare gli altri server).',
    '   Se --write-host auto fallisce (zero o più config trovati), specifica il tuo:',
    '   claude-desktop, cursor, vscode oppure openclaw.',
    '3. Verifica: ricontrolla il file scritto e chiama android_status (deve rispondere il telefono).',
    '4. Riferisci in chiaro: file toccato, backup, esito (via lan|tailscale), SU QUALE MACCHINA hai operato.',
    '   Poi ricordami di riavviare/ricaricare il client MCP. Se qualcosa fallisce, incolla l\u2019errore esatto e fermati.',
    '',
    'SE IL TELEFONO NON È SULLA STESSA WI-FI: fermati e chiedimi l\u2019IP Tailscale mostrato',
    'dal telefono (100.x.y.z), poi rilancia aggiungendo --tailscale-url http://<ip>:8765.',
    'Non improvvisare mai URL o porte diverse da 8765.',
    '',
    'Regole: non stampare MAI la key per intero (solo primi4…ultimi4), non committare MAI',
    'file che la contengono, non cambiare impostazioni del telefono. Se la key smette di',
    'funzionare è stata ruotata: chiedimi di ricopiarla dal telefono.',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`Argomento non riconosciuto: ${a}`);
    const name = a.slice(2);
    if (name === 'check' || name === 'dry-run' || name === 'print-prompt') { out[name] = true; continue; }
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
  const repoRoot = path.join(
    path.dirname(fileURLToPath(import.meta.url)),
    '..',
  );

  if (args['print-prompt']) {
    // Solo stampa: nessun probe di rete, nessuna scrittura su disco.
    // Niente percorsi di QUESTA macchina: l'agente gira altrove.
    const prompt = buildAgentPrompt({
      key: args.key ?? process.env.ANDROID_MCP_TOKEN ?? null,
      serverKey: args['server-key'] ?? 'android',
    });
    return { ok: true, printed: true, prompt };
  }
  const bridgeAbs = path.join(repoRoot, 'bridge', 'server.js');
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

  const serverKey = args['server-key'] ?? 'android';
  const writeHost = args['write-host'];

  if (!args['dry-run']) {
    const status = await probeLive(config.mcpServers.android.env);
    writeConfigFile(outPath, config);
    const result = {
      ok: true,
      key: maskKey(key),
      out: outPath,
      via: status?.preferredTransport ?? 'unknown',
    };
    if (writeHost) {
      const target = writeHost === 'auto'
        ? detectHostFile()
        : { host: writeHost, ...resolveHostFile(writeHost) };
      const merged = mergeHostConfigFile(
        target.path, target.shape, serverKey, config.mcpServers.android,
      );
      result.host = target.host;
      result.hostPath = merged.path;
      result.hostBackup = merged.backup;
    }
    return result;
  }
  if (writeHost && writeHost !== 'auto') {
    const target = resolveHostFile(writeHost);
    return { ok: true, dryRun: true, key: maskKey(key), out: outPath, hostPath: target.path };
  }
  return { ok: true, dryRun: true, key: maskKey(key), out: outPath };
}

const invokedAsScript =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invokedAsScript) {
  main().then(
    result => {
      if (result.printed) {
        console.log(result.prompt);
        return;
      }
      console.log(`OK: telefono raggiungibile via ${result.via ?? 'verifica saltata (dry-run)'}.`);
      console.log(`Config scritta in: ${result.out}`);
      if (result.hostPath) {
        console.log(`Host ${result.host ?? ''} aggiornato in: ${result.hostPath}`);
        if (result.hostBackup) console.log(`Backup precedente in: ${result.hostBackup}`);
        console.log('Riavvia/ricarica il client MCP e chiama android_status.');
      } else {
        console.log('Prossimo passo: punta il tuo client MCP a questo file e chiama android_status.');
      }
      console.log(`Key usata: ${result.key} (mai mostrata per intero, mai committata).`);
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
