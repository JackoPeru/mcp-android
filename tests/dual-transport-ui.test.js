import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';

test('main activity exposes LAN and Tailscale transport status', () => {
  const source = fs.readFileSync('android/app/src/main/java/com/example/androidmcp/MainActivity.java', 'utf8');
  const strings = fs.readFileSync('android/app/src/main/res/values/strings.xml', 'utf8');
  assert.match(strings, /LAN:/);
  assert.match(strings, /Tailscale:/);
  assert.match(strings, /Preferita:/);
  assert.match(source, /transportStatus\(\)/);
  assert.match(source, /R\.string\.transport_status/);
  assert.match(source, /McpForegroundService\.sessionEnabled\(\)/);
});
