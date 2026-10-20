import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createIosMcpServer } from '../bridge/ios-server.js';
import { IosClient, readIosConfig } from '../bridge/ios-client.js';
import { discoveryProof as iosDiscoveryProof } from '../bridge/ios-discovery.js';
import { encryptRequest, decryptRequest, encryptResponse, decryptResponse, helloProof, verifyHello } from '../bridge/ios-channel.js';

test('iOS MCP discovers 29 tools (28 + batch), validates paths and blocks cross-app methods', async t => {
  const calls = [];
  const server = createIosMcpServer({ call: async (method, params) => {
    calls.push({ method, params });
    if (method === 'screenshot') return { mimeType: 'image/png', data: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).toString('base64') };
    return { ok: true };
  } });
  const client = new Client({ name: 'test', version: '1' });
  const [ct, st] = InMemoryTransport.createLinkedPair();
  await server.connect(st); await client.connect(ct);
  t.after(async () => { await client.close(); await server.close(); });
  const tools = await client.listTools();
  assert.equal(tools.tools.length, 29);
  assert.ok(tools.tools.some(tool => tool.name === 'ios_shortcut_run'));
  assert.ok(tools.tools.some(tool => tool.name === 'ios_file_read'));
  assert.ok(!tools.tools.some(tool => tool.name === 'ios_shell'));
  assert.ok(!tools.tools.some(tool => tool.name === 'ios_tap'));
  assert.ok(!tools.tools.some(tool => tool.name === 'ios_shizuku_shell'));
  for (const path of ['../secret', '/etc', 'a/../b', 'content://secret', 'a\\b']) {
    const count = calls.length;
    const result = await client.callTool({ name: 'ios_file_read', arguments: { rootId: 'app-documents', path } });
    assert.equal(result.isError, true, path);
    assert.equal(calls.length, count);
  }
  const badUri = await client.callTool({ name: 'ios_open_uri', arguments: { uri: 'file:///etc/passwd' } });
  assert.equal(badUri.isError, true);
  const okUri = await client.callTool({ name: 'ios_open_uri', arguments: { uri: 'shortcuts://run-shortcut?name=Test' } });
  assert.equal(okUri.isError, undefined);
});

test('iOS LAN channel round-trips request/response with separated keys and bound nonces', () => {
  const token = 'ab'.repeat(32);
  const session = 'cd'.repeat(16);
  const req = encryptRequest(token, session, { method: 'status', params: {} });
  assert.deepEqual(decryptRequest(token, session, req), { method: 'status', params: {} });
  assert.throws(() => decryptResponse(token, session, req));
  const res = encryptResponse(token, session, { result: { ok: true }, requestNonce: req.nonce });
  assert.deepEqual(decryptResponse(token, session, res), { result: { ok: true }, requestNonce: req.nonce });
  const hello = helloProof(token, 'n-1', session);
  const payload = { protocol: 'mcp-ios-lan-channel', version: 1, nonce: 'n-1', session, proof: hello };
  assert.equal(verifyHello(token, 'n-1', payload), session);
  assert.equal(verifyHello(token, 'other', payload), null);
});

test('iOS discovery proof is domain-separated from Android', () => {
  const token = 'ff'.repeat(32);
  const proof = iosDiscoveryProof(token, { protocol: 'mcp-ios-discovery', version: 1, nonce: 'n', address: '192.168.1.20', port: 8765, transport: 'lan' });
  assert.match(proof, /^[a-f0-9]{64}$/);
  assert.throws(() => iosDiscoveryProof(token, { protocol: 'mcp-android-discovery', version: 1, nonce: 'n', address: '192.168.1.20', port: 8765, transport: 'lan' }));
});

test('iOS client requires IOS_MCP_TOKEN and accepts loopback only for tests', () => {
  assert.throws(() => readIosConfig({ IOS_MCP_TOKEN: 'short' }), /64 hex/i);
  const loopback = new IosClient({ url: 'http://127.0.0.1:8765', token: 'ab'.repeat(32) });
  assert.ok(loopback.url.includes('127.0.0.1'));
  assert.throws(() => new IosClient({ url: 'http://8.8.8.8:8765', token: 'ab'.repeat(32) }), /validated/);
});
