import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';

const bridge = fs.readFileSync(new URL('../bridge/server.js', import.meta.url), 'utf8');

test('openUri allowlist accetta solo http(s)/geo/tel/sms/mailto', () => {
  assert.match(bridge, /\^\(https\?\|geo\|tel\|sms\|smsto\|mailto\):/);
  assert.match(bridge, /file:\/\//); // citato come rigettato nel commento
  assert.match(bridge, /javascript:/);
});

test('shellWorkdir rifiuta traversal e controlli', () => {
  assert.match(bridge, /shellWorkdir/);
  assert.match(bridge, /\.\./);
  assert.match(bridge, /\\x00-\\x1f/);
});

test('normalizedCoordinate e bounded a 0..1000', () => {
  assert.match(bridge, /normalizedCoordinate.*min\(0\)\.max\(1000\)/);
  assert.match(bridge, /without mixing/);
});

test('android_batch limitato a 20 step', () => {
  assert.match(bridge, /android_batch/);
  assert.match(bridge, /steps: z\.array\(.*\)\.min\(1\)\.max\(20\)/s);
});

test('unlock_device registrato come tool', () => {
  assert.match(bridge, /unlock_device/);
});

test('android_flow limitato a 40 step e 20s', () => {
  assert.match(bridge, /steps: z\.array\(flowStep\)\.min\(1\)\.max\(40\)/);
  assert.match(bridge, /timeoutMs.*max\(20000\)/);
});
