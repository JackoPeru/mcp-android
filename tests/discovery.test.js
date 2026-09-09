import assert from 'node:assert/strict';
import test from 'node:test';
import dgram from 'node:dgram';
import { once } from 'node:events';
import * as discovery from '../bridge/discovery.js';

test('one-shot discovery sends no token and validates nonce before accepting candidate', async t => {
  const fixture = dgram.createSocket('udp4');
  fixture.bind(0, '127.0.0.1');
  await once(fixture, 'listening');
  t.after(() => fixture.close());
  let requestText = '';
  fixture.on('message', (message, remote) => {
    requestText = message.toString('utf8');
    const request = JSON.parse(requestText);
    const bad = Buffer.from(JSON.stringify({
      protocol: 'mcp-android-discovery', version: 1, nonce: 'wrong', address: '192.168.1.50', port: 8765, transport: 'lan',
    }));
    const good = Buffer.from(JSON.stringify({
      protocol: 'mcp-android-discovery', version: 1, nonce: request.nonce, address: '192.168.1.84', port: 8765, transport: 'lan',
    }));
    fixture.send(bad, remote.port, remote.address);
    fixture.send(good, remote.port, remote.address);
  });
  const candidates = await discovery.discoverLan({
    timeoutMs: 120,
    port: fixture.address().port,
    targets: [{ broadcast: '127.0.0.1' }],
  });
  assert.deepEqual(candidates, []);
  assert.doesNotMatch(requestText.toLowerCase(), /token|authorization|bearer/);
});

test('discovery accepts only a payload address matching the UDP source on one queried subnet', () => {
  assert.equal(typeof discovery.validateDiscoveryResponse, 'function');
  const nonce = 'abc123';
  const message = Buffer.from(JSON.stringify({
    protocol: 'mcp-android-discovery', version: 1, nonce,
    address: '192.168.1.84', port: 8765, transport: 'lan',
  }));
  const targets = [{ address: '192.168.1.10', netmask: '255.255.255.0', broadcast: '192.168.1.255' }];
  assert.equal(
    discovery.validateDiscoveryResponse(message, nonce, '192.168.1.84', targets),
    'http://192.168.1.84:8765/',
  );
  assert.equal(discovery.validateDiscoveryResponse(message, nonce, '192.168.1.85', targets), null);
  const offSubnet = Buffer.from(JSON.stringify({
    protocol: 'mcp-android-discovery', version: 1, nonce,
    address: '192.168.2.84', port: 8765, transport: 'lan',
  }));
  assert.equal(discovery.validateDiscoveryResponse(offSubnet, nonce, '192.168.2.84', targets), null);
});

test('discovery timeout is bounded and malformed responses are ignored', async () => {
  const started = Date.now();
  const candidates = await discovery.discoverLan({ timeoutMs: 60, port: 65500, targets: [{ broadcast: '127.0.0.1' }] });
  assert.deepEqual(candidates, []);
  assert.ok(Date.now() - started < 1000);
});
