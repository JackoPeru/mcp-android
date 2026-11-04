import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import {
  DEFAULT_OUT,
  buildClientConfig,
  main,
  maskKey,
  normalizeKey,
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

test('file generato e prompt restano fuori da git', () => {
  const gitignore = fs.readFileSync(new URL('../.gitignore', import.meta.url), 'utf8');
  assert.match(gitignore, /^mcp-config\.android\.json$/m);
});
