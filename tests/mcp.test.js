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
  assert.equal((await client.listTools()).tools.length, 66);
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
  const maxChunk = Buffer.alloc(32 * 1024).toString('base64');
  const acceptedChunk = await client.callTool({ name: 'android_file_write', arguments: { rootId: 'docs', path: 'chunk.bin', data: maxChunk } });
  assert.equal(acceptedChunk.isError, undefined);
  const oversizedChunk = await client.callTool({ name: 'android_file_write', arguments: { rootId: 'docs', path: 'chunk.bin', data: Buffer.alloc(32 * 1024 + 1).toString('base64') } });
  assert.equal(oversizedChunk.isError, true);
  const selector = await client.callTool({ name: 'android_ui_find', arguments: { textContains: 'OK' } });
  assert.equal(selector.isError, undefined);
  const ownApp = await client.callTool({ name: 'android_screen_context', arguments: { includeOwnApp: true } });
  assert.equal(ownApp.isError, undefined);
  assert.deepEqual(calls.at(-1), {
    method: 'screen_context',
    params: { treeMode: 'compact', screenshot: false, includeInvisible: false, maxNodes: 250, includeOwnApp: true },
  });
  const ownAppClick = await client.callTool({
    name: 'android_ui_click', arguments: { text: 'Avvia', includeOwnApp: true },
  });
  assert.equal(ownAppClick.isError, undefined);
  assert.equal(calls.at(-1).params.includeOwnApp, true);
  await client.callTool({ name: 'android_file_read', arguments: { rootId: 'docs', path: 'report.pdf' } });
  assert.deepEqual(calls.at(-1), { method: 'file_read', params: { rootId: 'docs', path: 'report.pdf', offset: 0, length: 65536 } });
  const invalidTap = await client.callTool({ name: 'android_tap', arguments: { x: -1, y: 10 } });
  assert.equal(invalidTap.isError, true);
  const beforeComposite = calls.length;
  const unsafeComposite = await client.callTool({
    name: 'android_act_and_observe',
    arguments: { action: { method: 'shell', params: { script: 'id' } } },
  });
  assert.equal(unsafeComposite.isError, true);
  assert.equal(calls.length, beforeComposite);
  const composite = await client.callTool({
    name: 'android_act_and_observe',
    arguments: {
      action: { method: 'ui_click', params: { text: 'Continue' } },
      wait: { mode: 'none' },
      observe: { mode: 'diff', screenshot: false },
    },
  });
  assert.equal(composite.isError, undefined);
  assert.equal(calls.at(-1).method, 'act_and_observe');
  const beforeUnsafeFlow = calls.length;
  const unsafeFlow = await client.callTool({
    name: 'android_flow',
    arguments: { steps: [{ type: 'shell', params: { script: 'id' } }] },
  });
  assert.equal(unsafeFlow.isError, true);
  assert.equal(calls.length, beforeUnsafeFlow);
  const flow = await client.callTool({
    name: 'android_flow',
    arguments: { steps: [{ type: 'observe', params: {} }], timeoutMs: 1000 },
  });
  assert.equal(flow.isError, undefined);
  assert.equal(calls.at(-1).method, 'flow');
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
  assert.equal(tools.length, 66);
  assert.ok(tools.find(t => t.name === 'android_screen_context'));
  assert.ok(tools.find(t => t.name === 'android_screen_diff'));
  assert.ok(tools.find(t => t.name === 'android_wait_idle'));
  assert.ok(tools.find(t => t.name === 'android_scroll_to'));
  assert.ok(tools.find(t => t.name === 'android_double_tap'));
  assert.ok(tools.find(t => t.name === 'android_press_key'));
  assert.ok(tools.find(t => t.name === 'android_act_and_observe'));
  assert.ok(tools.find(t => t.name === 'android_flow'));
  assert.ok(tools.find(t => t.name === 'android_capabilities'));
  assert.ok(tools.find(t => t.name === 'android_force_stop_app'));
  assert.ok(tools.find(t => t.name === 'android_logcat'));
  assert.ok(tools.find(t => t.name === 'android_diagnostics'));
  assert.equal(tools.find(t => t.name === 'android_file_read').annotations.readOnlyHint, true);
  assert.equal(tools.find(t => t.name === 'android_file_write').annotations.readOnlyHint, false);
  assert.ok(tools.find(t => t.name === 'android_shell'));
  assert.ok(tools.find(t => t.name === 'android_shizuku_shell'));
  assert.ok(tools.find(t => t.name === 'android_batch'));
});

test('open_uri and shell workdir reject smuggled schemes and traversal', async t => {
  const calls = [];
  const server = createMcpServer({ call: async (method, params) => {
    calls.push({ method, params });
    return { ok: true };
  } });
  const client = new Client({ name: 'validation-test', version: '1' });
  const [ct, st] = InMemoryTransport.createLinkedPair();
  await server.connect(st); await client.connect(ct);
  t.after(async () => { await client.close(); await server.close(); });

  for (const uri of ['file:///etc/passwd', 'content://contacts', 'intent://evil#Intent;', 'javascript:alert(1)', 'ftp://example.com/x']) {
    const result = await client.callTool({ name: 'android_open_uri', arguments: { uri } });
    assert.equal(result.isError, true, uri);
  }
  for (const uri of ['https://example.com/x', 'http://192.168.1.1/', 'geo:45.4,9.2', 'tel:+3902', 'mailto:a@b.c', 'sms:+3902', 'SMSTO:+3902']) {
    const result = await client.callTool({ name: 'android_open_uri', arguments: { uri } });
    assert.equal(result.isError, undefined, uri);
  }
  assert.deepEqual(calls.filter(c => c.method === 'open_uri').map(c => c.params.uri), [
    'https://example.com/x', 'http://192.168.1.1/', 'geo:45.4,9.2', 'tel:+3902', 'mailto:a@b.c', 'sms:+3902', 'SMSTO:+3902',
  ]);

  for (const workdir of ['../etc', 'a/../../b', 'a\x00b', 'a\x1fb']) {
    for (const tool of ['android_shell', 'android_shizuku_shell']) {
      const result = await client.callTool({ name: tool, arguments: { script: 'id', workdir } });
      assert.equal(result.isError, true, `${tool} ${JSON.stringify(workdir)}`);
    }
  }
  const ok = await client.callTool({ name: 'android_shell', arguments: { script: 'id', workdir: '/data/local/tmp' } });
  assert.equal(ok.isError, undefined);
});

test('batch never continues after an outcome-unknown step even when failFast is false', async t => {
  const calls = [];
  const server = createMcpServer({ call: async (method, params) => {
    calls.push({ method, params });
    if (calls.length === 1) {
      const error = new Error('operation outcome unknown');
      error.kind = 'outcome_unknown';
      throw error;
    }
    return { ok: true };
  } });
  const client = new Client({ name: 'batch-outcome-test', version: '1' });
  const [ct, st] = InMemoryTransport.createLinkedPair();
  await server.connect(st); await client.connect(ct);
  t.after(async () => { await client.close(); await server.close(); });

  const result = await client.callTool({
    name: 'android_batch',
    arguments: {
      failFast: false,
      steps: [
        { method: 'tap', params: { x: 10, y: 10 } },
        { method: 'tap', params: { x: 20, y: 20 } },
      ],
    },
  });
  const payload = JSON.parse(result.content[0].text);
  assert.equal(calls.length, 1);
  assert.equal(payload.results.length, 1);
  assert.equal(payload.results[0].ok, false);
});
