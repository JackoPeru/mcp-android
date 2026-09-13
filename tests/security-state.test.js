import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const server = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/McpHttpServer.java', import.meta.url), 'utf8');
const discovery = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/LanDiscoveryResponder.java', import.meta.url), 'utf8');
const events = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/EventJournal.java', import.meta.url), 'utf8');
const loops = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/UiLoopEngine.java', import.meta.url), 'utf8');
const accessibility = fs.readFileSync(new URL('../android/app/src/main/java/com/example/androidmcp/McpAccessibilityService.java', import.meta.url), 'utf8');

test('LAN secure session and replay guard are published atomically', () => {
  assert.match(server, /volatile\s+LanSessionState\s+lanState/);
  assert.doesNotMatch(server, /volatile\s+String\s+lanSession/);
  assert.doesNotMatch(server, /volatile\s+LanSecureChannel\.ReplayGuard\s+lanReplayGuard/);
});

test('rate limits and idle age use monotonic clocks', () => {
  assert.match(discovery, /SystemClock\.elapsedRealtime\(\)/);
  assert.match(events, /System\.nanoTime\(\)/);
  assert.match(loops, /lastEventAgeMs\(\)/);
});

test('native Accessibility operations cannot remain permanently busy after a lost gesture callback', () => {
  assert.match(accessibility, /AtomicLong\s+nativeGeneration/);
  assert.match(accessibility, /releaseNative\(long\s+generation\)/);
  assert.match(accessibility, /postDelayed\([^;]*releaseNative\(operationGeneration\)/s);
});
