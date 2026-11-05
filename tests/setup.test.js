import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import {
  DEFAULT_OUT,
  KEY_PLACEHOLDER,
  buildAgentPrompt,
  buildClientConfig,
  detectHostFile,
  main,
  maskKey,
  mergeHostConfigFile,
  normalizeKey,
  resolveHostFile,
  validateKey,
} from '../scripts/collega-telefono.mjs';

const KEY = 'a'.repeat(64);

test('validateKey accetta 64 hex, normalizza maiuscole/spazi', () => {
  assert.equal(validateKey(KEY), KEY);
  assert.equal(validateKey('  ' + 'A'.repeat(64) + '\n'), 'a'.repeat(64));
});

test('validateKey rifiuta key malformate senza rivelarle', () => {
  for (const bad of ['', 'abc', 'g'.repeat(64), 'a'.repeat(63), 'a'.repeat(65)]) {
    assert.throws(() => validateKey(bad), /64 caratteri esadecimali/);
  }
  // Il messaggio d'errore non deve mai contenere la key fornita.
  const secret = 'b'.repeat(64).slice(0, 63) + 'zz';
  try {
    validateKey(secret);
    assert.fail('doveva lanciare');
  } catch (error) {
    assert.ok(!String(error.message).includes(secret));
  }
});

test('normalizeKey e maskKey non espongono mai la key', () => {
  assert.equal(normalizeKey('  AB12  '), 'ab12');
  const masked = maskKey(KEY);
  assert.ok(!masked.includes(KEY));
  assert.ok(masked.startsWith('aaaa') && masked.endsWith('aaaa'));
  assert.equal(maskKey('short'), '****');
});

test('buildClientConfig produce env auto+discovery e path assoluto del bridge', () => {
  const cfg = buildClientConfig({ key: KEY, bridgePath: '/x/bridge/server.js' });
  const env = cfg.mcpServers.android.env;
  assert.equal(env.ANDROID_MCP_TOKEN, KEY);
  assert.equal(env.ANDROID_MCP_TRANSPORT, 'auto');
  assert.equal(env.ANDROID_MCP_DISCOVERY, 'true');
  assert.ok(!('ANDROID_MCP_TAILSCALE_URL' in env));
  assert.deepEqual(cfg.mcpServers.android.args, ['/x/bridge/server.js']);
  const withTs = buildClientConfig({
    key: KEY,
    tailscaleUrl: 'http://100.1.2.3:8765',
    bridgePath: '/x/bridge/server.js',
  });
  assert.equal(withTs.mcpServers.android.env.ANDROID_MCP_TAILSCALE_URL, 'http://100.1.2.3:8765');
});

test('main --dry-run non tocca rete né disco', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-setup-'));
  const before = fs.readdirSync(dir);
  const result = await main(['--key', KEY, '--dry-run'], dir);
  assert.equal(result.ok, true);
  assert.equal(result.dryRun, true);
  assert.equal(result.key, maskKey(KEY));
  assert.deepEqual(fs.readdirSync(dir), before);
  assert.ok(result.out.endsWith(DEFAULT_OUT));
});

test('main --check segnala key su disco diversa senza stamparla', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-setup-'));
  const disk = buildClientConfig({ key: 'c'.repeat(64), bridgePath: '/x/bridge/server.js' });
  fs.writeFileSync(path.join(dir, DEFAULT_OUT), JSON.stringify(disk));
  await assert.rejects(main(['--key', KEY, '--check'], dir), /non coincide/);
});

test('main senza key chiede la key senza ambiguità', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-setup-'));
  const saved = process.env.ANDROID_MCP_TOKEN;
  delete process.env.ANDROID_MCP_TOKEN;
  try {
    await assert.rejects(main(['--dry-run'], dir), /Key mancante/);
  } finally {
    if (saved !== undefined) process.env.ANDROID_MCP_TOKEN = saved;
  }
});

test('resolveHostFile mappa ogni host al file giusto', () => {
  const opts = { APPDATA: 'C:/A', HOME: 'C:/H', cwd: 'C:/W' };
  assert.ok(resolveHostFile('claude-desktop', opts).path.endsWith('claude_desktop_config.json'));
  assert.ok(resolveHostFile('cursor', opts).path.includes('.cursor'));
  assert.ok(resolveHostFile('vscode', opts).path.includes('.vscode'));
  assert.ok(resolveHostFile('openclaw', opts).path.includes('.openclaw'));
  assert.equal(resolveHostFile('vscode', opts).shape, 'servers');
  assert.equal(resolveHostFile('openclaw', opts).shape, 'mcp.servers');
  assert.throws(() => resolveHostFile('nope', opts), /non riconosciuto/);
});

test('detectHostFile: zero, uno o troppi config esistenti', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-host-'));
  const opts = { APPDATA: path.join(dir, 'A'), HOME: path.join(dir, 'H'), cwd: path.join(dir, 'W') };
  assert.throws(() => detectHostFile({ opts }), /Nessun config host/);
  const only = resolveHostFile('cursor', opts);
  fs.mkdirSync(path.dirname(only.path), { recursive: true });
  fs.writeFileSync(only.path, '{}');
  assert.equal(detectHostFile({ opts }).host, 'cursor');
  const second = resolveHostFile('openclaw', opts);
  fs.mkdirSync(path.dirname(second.path), { recursive: true });
  fs.writeFileSync(second.path, '{}');
  assert.throws(() => detectHostFile({ opts }), /più config host/);
});

test('mergeHostConfigFile crea, fonde e fa backup senza toccare le altre entry', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-merge-'));
  const target = path.join(dir, 'mcp.json');
  const entry = { command: 'node', args: ['/x/bridge/server.js'], env: { ANDROID_MCP_TOKEN: KEY } };
  const created = mergeHostConfigFile(target, 'mcpServers', 'android', entry);
  assert.equal(created.backup, null);
  let doc = JSON.parse(fs.readFileSync(target, 'utf8'));
  assert.deepEqual(doc.mcpServers.android, entry);

  const again = mergeHostConfigFile(target, 'mcpServers', 'android', entry);
  assert.ok(again.backup && fs.existsSync(again.backup));

  // Le altre entry restano intatte.
  doc.mcpServers.altro = { command: 'x' };
  fs.writeFileSync(target, JSON.stringify(doc));
  mergeHostConfigFile(target, 'mcpServers', 'android', entry);
  doc = JSON.parse(fs.readFileSync(target, 'utf8'));
  assert.deepEqual(doc.mcpServers.altro, { command: 'x' });

  // Forma vscode: avvolge con type stdio.
  const vsc = path.join(dir, 'vsc.json');
  mergeHostConfigFile(vsc, 'servers', 'android', entry);
  doc = JSON.parse(fs.readFileSync(vsc, 'utf8'));
  assert.equal(doc.servers.android.type, 'stdio');

  // JSON rotto o forma inattesa: non tocca niente.
  const broken = path.join(dir, 'broken.json');
  fs.writeFileSync(broken, '{non json');
  assert.throws(() => mergeHostConfigFile(broken, 'mcpServers', 'android', entry), /illeggibile/);
  const weird = path.join(dir, 'weird.json');
  fs.writeFileSync(weird, '[]');
  assert.throws(() => mergeHostConfigFile(weird, 'mcpServers', 'android', entry), /forma inattesa/);
});

test('main --dry-run --write-host calcola il path senza scrivere', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-dry-'));
  const result = await main(['--key', KEY, '--dry-run', '--write-host', 'cursor'], dir);
  assert.equal(result.dryRun, true);
  assert.ok(result.hostPath.includes('.cursor'));
  assert.deepEqual(fs.readdirSync(dir), []);
});

test('buildAgentPrompt compila i valori veri, placeholder senza key', () => {
  const withKey = buildAgentPrompt({ key: KEY, repoRoot: 'C:/repo', serverKey: 'android' });
  assert.ok(withKey.includes(KEY));
  assert.ok(withKey.includes('C:/repo'));
  assert.ok(withKey.includes('C:/repo/bridge/server.js') || withKey.includes('C:\\repo\\bridge\\server.js'));
  assert.ok(withKey.includes('--write-host auto'));
  assert.ok(!withKey.includes(KEY_PLACEHOLDER));
  const withoutKey = buildAgentPrompt({ repoRoot: 'C:/repo' });
  assert.ok(withoutKey.includes(KEY_PLACEHOLDER));
  assert.ok(!withoutKey.includes(KEY));
  assert.throws(() => buildAgentPrompt({ key: 'corta', repoRoot: 'C:/repo' }), /64 caratteri esadecimali/);
  const custom = buildAgentPrompt({ repoRoot: 'C:/repo', serverKey: 'casa' });
  assert.ok(custom.includes('server "casa"'));
});

test('main --print-prompt stampa senza rete né disco', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcp-prompt-'));
  const saved = process.env.ANDROID_MCP_TOKEN;
  delete process.env.ANDROID_MCP_TOKEN;
  try {
    const withPlaceholder = await main(['--print-prompt'], dir);
    assert.equal(withPlaceholder.printed, true);
    assert.ok(withPlaceholder.prompt.includes(KEY_PLACEHOLDER));
    const withKey = await main(['--print-prompt', '--key', KEY], dir);
    assert.ok(withKey.prompt.includes(KEY));
    assert.ok(!withKey.prompt.includes(KEY_PLACEHOLDER));
  } finally {
    if (saved !== undefined) process.env.ANDROID_MCP_TOKEN = saved;
  }
  assert.deepEqual(fs.readdirSync(dir), []);
});

test('file generato e prompt restano fuori da git', () => {
  const gitignore = fs.readFileSync(new URL('../.gitignore', import.meta.url), 'utf8');
  assert.match(gitignore, /^mcp-config\.android\.json$/m);
});
