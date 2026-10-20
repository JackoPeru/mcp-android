import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { pathToFileURL } from 'node:url';
import { IosClient, readIosConfig } from './ios-client.js';

export const IOS_MCP_VERSION = '0.8.20';

const FILE_WRITE_MAX_BYTES = 32 * 1024;
const FILE_WRITE_MAX_BASE64_CHARS = Math.ceil(FILE_WRITE_MAX_BYTES / 3) * 4;
const PNG_MAGIC = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const SCREENSHOT_MAX_CHARS = 8 * 1024 * 1024;
export function isValidPng(data) {
  if (typeof data !== 'string' || data.length > SCREENSHOT_MAX_CHARS) return false;
  let buf;
  try { buf = Buffer.from(data, 'base64'); } catch { return false; }
  return buf.subarray(0, 8).equals(PNG_MAGIC);
}
const openUri = z.string().min(1).max(2048).regex(
  /^(https?|geo|tel|sms|smsto|mailto|shortcuts):/i,
  'Use an http(s), geo, tel, sms/smsto, mailto or shortcuts URI',
);
const path = z.string().max(1024).refine(value => value === '' || (
  !value.startsWith('/') && !/[\\\x00-\x1f:]/.test(value) &&
  value.split('/').every(part => part && part !== '.' && part !== '..')
), 'Use a relative path without empty, dot, parent, URI, or backslash components').default('');
const nonEmptyPath = z.string().min(1).max(1024).refine(value => (
  !value.startsWith('/') && !/[\\\x00-\x1f:]/.test(value) &&
  value.split('/').every(part => part && part !== '.' && part !== '..')
), 'Use a non-empty relative path without dot, parent, URI, or backslash components');
const root = { rootId: z.string().regex(/^[A-Za-z0-9_-]{1,80}$/), path };
const rootId = z.string().regex(/^[A-Za-z0-9_-]{1,80}$/);
const base64 = z.string().max(FILE_WRITE_MAX_BASE64_CHARS)
  .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/)
  .refine(value => Buffer.byteLength(value, 'base64') <= FILE_WRITE_MAX_BYTES, 'File write chunk exceeds 32 KiB');

// Sottoinsieme realizzabile su iOS stock. Niente controllo cross-app,
// niente shell/logcat/force-stop, niente tap di sistema, niente sblocco,
// niente notification listener: vedi docs/ios-parity.md.
const definitions = [
  ['status', 'iPhone state and enabled capabilities. Start here.', {}, true],
  ['screen_context', 'In-app semantic context for the MCP iOS app itself (never other apps). screenshot:true requires the app in the foreground.', { screenshot: z.boolean().default(false) }, true],
  ['screenshot', 'Capture the MCP iOS app window while the app is in the foreground. iOS forbids capturing other apps.', {}, true],
  ['ui_tree', 'Accessibility nodes of the MCP iOS app itself, never other apps.', {}, true],
  ['clipboard_get', 'Read the current plain-text clipboard when iOS permits it (foreground).', {}, true],
  ['clipboard_set', 'Replace the current clipboard with plain text.', { text: z.string().max(4096) }, false],
  ['device_info', 'Read iPhone model, iOS version, battery and audio volume state.', {}, true],
  ['open_uri', 'Open an http(s), geo, tel dialer, mailto, sms or shortcuts URI. This does not place a call.', { uri: openUri }, false],
  ['share_text', 'Queue text in the iOS share sheet while the app is in the foreground.', { text: z.string().max(4096), title: z.string().max(120).default('') }, false],
  ['apps', 'List known URL schemes and shortcuts (iOS has no launchable-app query).', { query: z.string().max(100).default(''), limit: z.number().int().min(1).max(500).default(100) }, true],
  ['launch_app', 'Open an explicit https or shortcuts URL scheme.', { urlScheme: z.string().max(2048).regex(/^(https?|shortcuts):/i) }, false],
  ['shortcut_run', 'Run an iOS Shortcut by name (native iOS automation path). The shortcut must already exist on the phone.', { name: z.string().min(1).max(200), input: z.string().max(4096).default('') }, false],
  ['notifications', 'List own-app delivered notifications. Cross-app notification access is unavailable on iOS.', { limit: z.number().int().min(1).max(200).default(50) }, true],
  ['volume_get', 'Read the current app audio output volume. iOS exposes no settable system volume API.', {}, true],
  ['events', 'Read the bounded in-memory event feed after a cursor (file/clipboard/shortcut/lifecycle).', { afterId: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).default(0), limit: z.number().int().min(1).max(200).default(100) }, true],
  ['events_wait', 'Long-poll the in-memory event feed until a newer event arrives or timeout expires.', { afterId: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).default(0), limit: z.number().int().min(1).max(200).default(100), timeoutMs: z.number().int().min(0).max(12000).default(8000) }, true],
  ['capabilities', 'Report runtime feature availability. Cross-app UI control and shell are unsupported-on-ios by platform policy.', {}, true],
  ['diagnostics', 'Read metadata-only service diagnostics: transport, requests, snapshots, events.', {
    eventLimit: z.number().int().min(1).max(100).default(20),
    traceLimit: z.number().int().min(1).max(128).default(40),
  }, true],
  ['updater_check', 'Check the latest stable iOS release. Installation uses TestFlight/App Store, never silent install.', {}, true],
  ['file_roots', 'List authorized storage roots: app-documents plus imported UIDocumentPicker bookmarks.', {}, true],
  ['file_list', 'List entries in an authorized directory, paginated. Paths are relative to rootId.', { ...root, offset: z.number().int().min(0).max(1000000).default(0), limit: z.number().int().min(1).max(200).default(100) }, true],
  ['file_stat', 'Read file or directory metadata within an authorized root.', root, true],
  ['file_read', 'Read bytes from an authorized file. Returns base64; use offset and length for subsequent blocks.', { ...root, offset: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).default(0), length: z.number().int().min(1).max(262144).default(65536) }, true],
  ['file_search', 'Recursively search names below an authorized directory.', { ...root, query: z.string().max(255).default(''), maxDepth: z.number().int().min(0).max(16).default(8), limit: z.number().int().min(1).max(500).default(100) }, true],
  ['file_write', 'Create or write a base64 block up to 32 KiB inside the sandbox or an imported folder.', { rootId, path: nonEmptyPath, data: base64, offset: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).default(0), truncate: z.boolean().default(true), mimeType: z.string().min(1).max(200).default('application/octet-stream') }, false],
  ['file_mkdir', 'Create a directory inside an authorized root.', { rootId, path: nonEmptyPath }, false],
  ['file_rename', 'Rename a file or directory inside an authorized root.', { rootId, path: nonEmptyPath, newName: z.string().min(1).max(255).refine(v => !/[\\/\x00-\x1f:]/.test(v) && v !== '.' && v !== '..') }, false],
  ['file_delete', 'Delete a file or directory. The authorized root itself cannot be deleted.', { rootId, path: nonEmptyPath }, false],
];

export function createIosMcpServer(client) {
  const server = new McpServer({ name: 'ios-private-mcp', version: IOS_MCP_VERSION });
  const schemas = new Map();
  for (const [method, description, shape, readOnly] of definitions) {
    const schema = z.object(shape).strict();
    schemas.set(method, schema);
    server.registerTool(`ios_${method}`, {
      description, inputSchema: schema,
      annotations: { readOnlyHint: readOnly, destructiveHint: !readOnly, idempotentHint: readOnly, openWorldHint: true },
    }, async args => {
      try {
        const result = await client.call(method, schema.parse(args));
        if (method === 'screenshot') {
          if (!result || result.mimeType !== 'image/png' || !isValidPng(result.data)) {
            throw new Error('Invalid iOS screenshot');
          }
          return { content: [{ type: 'image', data: result.data, mimeType: result.mimeType }] };
        }
        if (method === 'screen_context' && result?.screenshot?.mimeType === 'image/png' &&
            typeof result.screenshot.data === 'string') {
          const screenshot = result.screenshot;
          if (!isValidPng(screenshot.data)) throw new Error('Invalid iOS composite screenshot');
          const textResult = { ...result };
          delete textResult.screenshot;
          return { content: [
            { type: 'image', data: screenshot.data, mimeType: 'image/png' },
            { type: 'text', text: JSON.stringify(textResult) },
          ] };
        }
        return { content: [{ type: 'text', text: JSON.stringify(result) }] };
      } catch (error) {
        return { isError: true, content: [{ type: 'text', text: error.message }] };
      }
    });
  }
  const batchMethods = new Set([
    'ui_tree', 'clipboard_get', 'clipboard_set', 'device_info', 'open_uri', 'share_text',
    'apps', 'launch_app', 'shortcut_run', 'notifications', 'volume_get', 'events', 'events_wait',
  ]);
  const batchSchema = z.object({
    steps: z.array(z.object({
      method: z.string().refine(value => batchMethods.has(value), 'Method is not allowed in a batch'),
      params: z.record(z.string(), z.unknown()).default({}),
    }).strict()).min(1).max(20),
    failFast: z.boolean().default(true),
  }).strict();
  server.registerTool('ios_batch', {
    description: 'Run up to 20 validated operations sequentially to reduce round trips. Screenshots and file mutations are intentionally excluded.',
    inputSchema: batchSchema,
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
  }, async args => {
    try {
      const request = batchSchema.parse(args);
      const results = [];
      for (let i = 0; i < request.steps.length; i++) {
        const step = request.steps[i];
        const schema = schemas.get(step.method);
        try {
          const params = schema.parse(step.params);
          results.push({ index: i, method: step.method, ok: true, result: await client.call(step.method, params) });
        } catch (error) {
          results.push({ index: i, method: step.method, ok: false, error: error.message,
            ...(error?.kind ? { kind: error.kind } : {}) });
          if (request.failFast || error?.kind === 'outcome_unknown') break;
        }
      }
      return { content: [{ type: 'text', text: JSON.stringify({ results }) }] };
    } catch (error) {
      return { isError: true, content: [{ type: 'text', text: error.message }] };
    }
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const server = createIosMcpServer(new IosClient(readIosConfig()));
    await server.connect(new StdioServerTransport());
  } catch {
    console.error('iOS MCP startup failed. Configure IOS_MCP_TOKEN plus a LAN/Tailscale endpoint or LAN discovery.');
    process.exitCode = 1;
  }
}
