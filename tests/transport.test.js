import assert from 'node:assert/strict';
import test from 'node:test';
import { TransportResolver, validateLanOrigin, validateTailscaleOrigin } from '../bridge/transport.js';
import { AndroidClient } from '../bridge/client.js';

test('transport validators keep LAN and Tailscale address spaces separate', () => {
  assert.equal(validateLanOrigin('http://192.168.1.10:8765'), 'http://192.168.1.10:8765/');
  assert.equal(validateTailscaleOrigin('http://100.100.1.2:8765'), 'http://100.100.1.2:8765/');
  for (const value of ['http://8.8.8.8:8765', 'http://100.100.1.2:8765', 'http://host.local:8765']) {
    assert.throws(() => validateLanOrigin(value));
  }
  for (const value of ['http://192.168.1.10:8765', 'http://100.128.0.1:8765', 'https://example.com']) {
    assert.throws(() => validateTailscaleOrigin(value));
  }
});

test('auto prefers validated LAN and avoids Tailscale', async () => {
  const probed = [];
  const resolver = new TransportResolver({
    preference: 'auto', lanUrl: 'http://192.168.1.84:8765/', tailscaleUrl: 'http://100.100.1.2:8765/', discovery: true,
  }, {
    discover: async () => [],
    probe: async url => { probed.push(url); return true; },
  });
  assert.deepEqual(await resolver.resolve(), { url: 'http://192.168.1.84:8765/', transport: 'lan' });
  assert.deepEqual(probed, ['http://192.168.1.84:8765/']);
});

test('auto discovers LAN then falls back to Tailscale only when LAN is unreachable', async () => {
  const resolver = new TransportResolver({
    preference: 'auto', lanUrl: null, tailscaleUrl: 'http://100.100.1.2:8765/', discovery: true,
  }, {
    discover: async () => ['http://192.168.1.99:8765/'],
    probe: async url => { if (url.includes('192.168.1.99')) throw Object.assign(new Error('unreachable'), { kind: 'unreachable' }); return true; },
  });
  assert.deepEqual(await resolver.resolve(), { url: 'http://100.100.1.2:8765/', transport: 'tailscale' });
});

test('configured LAN authentication failure is not treated as transport failure', async () => {
  const resolver = new TransportResolver({
    preference: 'auto', lanUrl: 'http://192.168.1.84:8765/', tailscaleUrl: 'http://100.100.1.2:8765/', discovery: true,
  }, {
    discover: async () => [],
    probe: async () => { throw Object.assign(new Error('auth'), { kind: 'auth' }); },
  });
  await assert.rejects(resolver.resolve(), /auth/i);
});

test('actual mutating RPC is dispatched once and never replayed across transports', async () => {
  let resolves = 0;
  let fetches = 0;
  const client = new AndroidClient({
    token: 'a'.repeat(64),
    resolver: { resolve: async () => { resolves++; return { url: 'http://192.168.1.84:8765/', transport: 'lan' }; } },
    fetchImpl: async () => {
      fetches++;
      const error = new TypeError('fetch failed');
      throw error;
    },
  });
  await assert.rejects(client.call('tap', { x: 10, y: 10 }), /outcome unknown|disconnected|unreachable/i);
  assert.equal(resolves, 1);
  assert.equal(fetches, 1);
});

test('recently validated LAN cache avoids a status probe before every RPC', async () => {
  let probes = 0;
  let now = 1_000;
  const resolver = new TransportResolver({
    preference: 'auto',
    lanUrl: 'http://192.168.1.84:8765/',
    tailscaleUrl: 'http://100.100.1.2:8765/',
    discovery: true,
  }, {
    discover: async () => [],
    probe: async () => { probes++; return true; },
    now: () => now,
    validationTtlMs: 5_000,
  });

  assert.equal((await resolver.resolve()).transport, 'lan');
  assert.equal(probes, 1);
  resolver.noteSuccess('http://192.168.1.84:8765/', 'lan');
  now += 1_000;
  assert.equal((await resolver.resolve()).transport, 'lan');
  assert.equal(probes, 1);
  now += 5_001;
  assert.equal((await resolver.resolve()).transport, 'lan');
  assert.equal(probes, 2);
});

test('LAN transport failure invalidates recent cache so the next RPC falls back to Tailscale', async () => {
  let now = 1_000;
  let lanAlive = true;
  const probes = [];
  const resolver = new TransportResolver({
    preference: 'auto',
    lanUrl: 'http://192.168.1.84:8765/',
    tailscaleUrl: 'http://100.100.1.2:8765/',
    discovery: false,
  }, {
    discover: async () => [],
    probe: async url => {
      probes.push(url);
      if (url.includes('192.168.1.84') && !lanAlive) {
        throw Object.assign(new Error('unreachable'), { kind: 'unreachable' });
      }
      return true;
    },
    now: () => now,
    validationTtlMs: 5_000,
  });

  const dispatched = [];
  const client = new AndroidClient({
    token: 'a'.repeat(64),
    resolver,
    fetchImpl: async url => {
      dispatched.push(url.hostname);
      if (url.hostname === '192.168.1.84' && !lanAlive) throw new TypeError('fetch failed');
      return new Response(JSON.stringify({ result: { transport: url.hostname } }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    },
  });

  assert.equal((await client.call('status')).transport, '192.168.1.84');
  lanAlive = false;
  now += 1_000;
  await assert.rejects(client.call('tap', { x: 10, y: 10 }), /outcome unknown|disconnected/i);
  assert.equal(dispatched.filter(host => host === '192.168.1.84').length, 2);

  assert.equal((await client.call('status')).transport, '100.100.1.2');
  assert.equal(dispatched.filter(host => host === '192.168.1.84').length, 2);
  assert.equal(dispatched.filter(host => host === '100.100.1.2').length, 1);
  assert.ok(probes.filter(url => url.includes('192.168.1.84')).length >= 2);
});
