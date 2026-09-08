import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createMcpServer } from '../bridge/server.js';

test('MCP discovers tools, validates file paths and routes without accessibility dependency', async t => {
  const calls = [];
  const server = createMcpServer({ call: async (method, params) => {
    calls.push({ method, params });
    if (method === 'ui_tree') throw new Error('Android ACCESSIBILITY_DISABLED');
    if (method === 'screenshot') return { mimeType: 'image/png', data: Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).toString('base64'), width: 800, height: 1200, displayWidth: 1600, displayHeight: 2400 };
    return { roots: [{ rootId: 'docs', name: 'Documenti' }] };
  } });
  const client = new Client({ name: 'test', version: '1' });
  const [ct, st] = InMemoryTransport.createLinkedPair();
  await server.connect(st); await client.connect(ct);
  t.after(async () => { await client.close(); await server.close(); });
  assert.equal((await client.listTools()).tools.length, 53);
  const ui = await client.callTool({ name: 'android_ui_tree', arguments: {} });
  assert.equal(ui.isError, true);
  const roots = await client.callTool({ name: 'android_file_roots', arguments: {} });
  assert.deepEqual(JSON.parse(roots.content[0].text).roots, [{ rootId: 'docs', name: 'Documenti' }]);
  for (const path of ['../secret', '/etc', 'a/../b', 'a//b', 'content://secret', 'a\\b', 'a\0b']) {
    const count = calls.length;
    const result = await client.callTool({ name: 'android_file_read', arguments: { rootId: 'docs', path } });
    assert.equal(result.isError, true, path);
    assert.equal(calls.length, count);
  }
  const oversized = await client.callTool({ name: 'android_file_read', arguments: { rootId: 'docs', path: 'a', length: 262145 } });
  assert.equal(oversized.isError, true);
  const traversalWrite = await client.callTool({ name: 'android_file_write', arguments: { rootId: 'docs', path: '../bad', data: '' } });
  assert.equal(traversalWrite.isError, true);
  const selector = await client.callTool({ name: 'android_ui_find', arguments: { textContains: 'OK' } });
  assert.equal(selector.isError, undefined);
  await client.callTool({ name: 'android_file_read', arguments: { rootId: 'docs', path: 'report.pdf' } });
  assert.deepEqual(calls.at(-1), { method: 'file_read', params: { rootId: 'docs', path: 'report.pdf', offset: 0, length: 65536 } });
  const invalidTap = await client.callTool({ name: 'android_tap', arguments: { x: -1, y: 10 } });
  assert.equal(invalidTap.isError, true);
  const shot = await client.callTool({ name: 'android_screenshot', arguments: {} });
  assert.equal(shot.content[0].type, 'image');
  assert.equal(JSON.parse(shot.content[1].text).displayWidth, 1600);
  const unknown = await client.callTool({ name: 'android_unknown', arguments: {} });
  assert.equal(unknown.isError, true);
});

test('actual stdio process initializes and exposes schemas without reaching a phone', async t => {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL('../bridge/server.js', import.meta.url))],
    env: { ...process.env, ANDROID_MCP_URL: 'http://100.100.1.2:8765', ANDROID_MCP_TOKEN: 'a'.repeat(64) },
    stderr: 'pipe',
  });
  const client = new Client({ name: 'stdio-test', version: '1' });
  await client.connect(transport);
  t.after(() => client.close());
  const tools = (await client.listTools()).tools;
  assert.equal(tools.length, 53);
  assert.ok(tools.find(t => t.name === 'android_screen_context'));
  assert.ok(tools.find(t => t.name === 'android_screen_diff'));
  assert.ok(tools.find(t => t.name === 'android_wait_idle'));
  assert.ok(tools.find(t => t.name === 'android_scroll_to'));
  assert.equal(tools.find(t => t.name === 'android_file_read').annotations.readOnlyHint, true);
  assert.equal(tools.find(t => t.name === 'android_file_write').annotations.readOnlyHint, false);
  assert.ok(tools.find(t => t.name === 'android_shell'));
  assert.ok(tools.find(t => t.name === 'android_shizuku_shell'));
  assert.ok(tools.find(t => t.name === 'android_batch'));
});
