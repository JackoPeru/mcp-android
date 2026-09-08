import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { AndroidClient, readConfig } from '../bridge/client.js';

const token = 'a'.repeat(64);
test('config only accepts explicit Tailscale IPv4, no credentials or URL redirects', () => {
  for (const host of ['https://example.com', 'http://192.168.1.5:8765', 'http://100.63.0.1', 'http://100.128.0.1', 'http://user:pass@100.64.0.1', 'http://100.64.0.1/path', 'http://100.64.0.1/?token=x']) {
    assert.throws(() => readConfig({ ANDROID_MCP_URL: host, ANDROID_MCP_TOKEN: token }));
  }
  assert.equal(readConfig({ ANDROID_MCP_URL: 'http://100.100.1.2:8765', ANDROID_MCP_TOKEN: token }).url, 'http://100.100.1.2:8765/');
  assert.throws(() => readConfig({ ANDROID_MCP_URL: 'http://100.100.1.2', ANDROID_MCP_TOKEN: 'short' }));
});

test('default client timeout leaves headroom above phone long operations', () => {
  const client = new AndroidClient({ url: 'http://100.100.1.2:8765/', token });
  assert.equal(client.timeoutMs, 30000);
});

test('real HTTP boundary: auth, result, error, redirect, bounded body and timeout', async (t) => {
  let redirected = false;
  const server = createServer(async (req, res) => {
    if (req.url === '/leak') { redirected = true; res.end('{}'); return; }
    if (req.headers.authorization !== `Bearer ${token}`) { res.writeHead(401); res.end('{}'); return; }
    let data = ''; for await (const chunk of req) data += chunk;
    const request = JSON.parse(data);
    if (request.method === 'redirect') { res.writeHead(302, { location: '/leak' }); res.end(); return; }
    if (request.method === 'large') { res.end('x'.repeat(2000)); return; }
    if (request.method === 'slow') { return; }
    if (request.method === 'bad') { res.end(JSON.stringify({ error: { code: 'INVALID_ARGUMENT', message: 'invalid path' } })); return; }
    if (request.method === 'revoked') { res.writeHead(400); res.end(JSON.stringify({ error: { code: 'ROOT_REVOKED', message: 'sensitive path omitted' } })); return; }
    res.end(JSON.stringify({ result: request }));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  // Loopback is a test fixture passed directly to the transport, never accepted by env config.
  const client = new AndroidClient({ url: `http://127.0.0.1:${server.address().port}/`, token, timeoutMs: 100, maxResponseBytes: 1024 });
  assert.deepEqual(await client.call('file_list', { rootId: 'root', path: 'docs' }), { method: 'file_list', params: { rootId: 'root', path: 'docs' } });
  await assert.rejects(client.call('bad', {}), /INVALID_ARGUMENT/);
  await assert.rejects(client.call('revoked', {}), /ROOT_REVOKED/);
  await assert.rejects(client.call('redirect', {}), /HTTP 302/);
  assert.equal(redirected, false);
  await assert.rejects(client.call('large', {}), /too large/);
  await assert.rejects(client.call('slow', {}), /timeout|timed out/i);
  const wrong = new AndroidClient({ url: client.url, token: 'b'.repeat(64) });
  await assert.rejects(wrong.call('status', {}), /HTTP 401/);
});
