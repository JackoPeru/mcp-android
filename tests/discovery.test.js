import assert from 'node:assert/strict';
import test from 'node:test';
import dgram from 'node:dgram';
import { once } from 'node:events';
import * as discovery from '../bridge/discovery.js';

const token = 'a'.repeat(64);
const { discoverLan, discoveryProof } = discovery;

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
      protocol: 'mcp-android-discovery', version: 1, nonce: request.nonce,
      address: '192.168.1.50', port: 8765, transport: 'lan', proof: '0'.repeat(64),
    }));
    const goodFields = {
      protocol: 'mcp-android-discovery', version: 1, nonce: request.nonce,
      address: '192.168.1.84', port: 8765, transport: 'lan',
    };
  const good = Buffer.from(JSON.stringify({
      ...goodFields,
      proof: discoveryProof(token, goodFields),
    }));
    fixture.send(bad, remote.port, remote.address);
    fixture.send(good, remote.port, remote.address);
  });
  const candidates = await discovery.discoverLan({
    timeoutMs: 120,
    port: fixture.address().port,
    targets: [{ broadcast: '127.0.0.1' }],
    token,
  });
  assert.deepEqual(candidates, []);
  assert.doesNotMatch(requestText.toLowerCase(), /token|authorization|bearer/);
});

test('discovery accepts only a payload address matching the UDP source on one queried subnet', () => {
  assert.equal(typeof discovery.validateDiscoveryResponse, 'function');
  const nonce = 'abc123';
  const fields = {
    protocol: 'mcp-android-discovery', version: 1, nonce,
    address: '192.168.1.84', port: 8765, transport: 'lan',
  };
  const message = Buffer.from(JSON.stringify({
    ...fields,
    proof: discoveryProof(token, fields),
  }));
  const targets = [{ address: '192.168.1.10', netmask: '255.255.255.0', broadcast: '192.168.1.255' }];
  assert.equal(
    discovery.validateDiscoveryResponse(message, nonce, token, '192.168.1.84', targets),
    'http://192.168.1.84:8765/',
  );
  assert.equal(discovery.validateDiscoveryResponse(message, nonce, token, '192.168.1.85', targets), null);
  const offSubnetFields = {
    protocol: 'mcp-android-discovery', version: 1, nonce,
    address: '192.168.2.84', port: 8765, transport: 'lan',
  };
  const offSubnet = Buffer.from(JSON.stringify({
    ...offSubnetFields,
    proof: discoveryProof(token, offSubnetFields),
  }));
  assert.equal(discovery.validateDiscoveryResponse(offSubnet, nonce, token, '192.168.2.84', targets), null);
});

test('discovery timeout is bounded and malformed responses are ignored', async () => {
  const started = Date.now();
  const candidates = await discoverLan({
    timeoutMs: 60, port: 65500, targets: [{ broadcast: '127.0.0.1' }], token,
  });
  assert.deepEqual(candidates, []);
  assert.ok(Date.now() - started < 1000);
});

test('one bad broadcast target does not abort discovery on another interface', async t => {
  const fixture = dgram.createSocket('udp4');
  fixture.bind(0, '127.0.0.1');
  await once(fixture, 'listening');
  t.after(() => fixture.close());
  fixture.on('message', (message, remote) => {
    const request = JSON.parse(message.toString('utf8'));
    const fields = {
      protocol: 'mcp-android-discovery', version: 1, nonce: request.nonce,
      address: '192.168.1.84', port: 8765, transport: 'lan',
    };
    const response = Buffer.from(JSON.stringify({
      ...fields, proof: discoveryProof(token, fields),
    }));
    fixture.send(response, remote.port, remote.address);
  });
  const candidates = await discoverLan({
    timeoutMs: 120,
    port: fixture.address().port,
    targets: [{ broadcast: '256.256.256.256' }, { broadcast: '127.0.0.1' }],
    token,
  });
  assert.deepEqual(candidates, []);
});

test('spoofed discovery response is rejected before any authenticated HTTP probe can occur', async t => {
  const fixture = dgram.createSocket('udp4');
  fixture.bind(0, '127.0.0.1');
  await once(fixture, 'listening');
  t.after(() => fixture.close());
  fixture.on('message', (message, remote) => {
    const request = JSON.parse(message.toString('utf8'));
    const response = Buffer.from(JSON.stringify({
      protocol: 'mcp-android-discovery', version: 1, nonce: request.nonce,
      address: '192.168.1.66', port: 8765, transport: 'lan',
      proof: 'f'.repeat(64),
    }));
    fixture.send(response, remote.port, remote.address);
  });
  const candidates = await discoverLan({
    timeoutMs: 120,
    port: fixture.address().port,
    targets: [{ broadcast: '127.0.0.1' }],
    token,
  });
  assert.deepEqual(candidates, []);
});

test('discovery proof matches Android test vector and never contains the bearer', () => {
  const fields = {
    protocol: 'mcp-android-discovery', version: 1, nonce: 'abc123',
    address: '192.168.1.84', port: 8765, transport: 'lan',
  };
  assert.equal(
    discoveryProof(token, fields),
    '48e4f3c7d40b48692381f3b647450190b6a0ffade5f6b8db5a3090b9c4800471',
  );
  assert.doesNotMatch(JSON.stringify(fields), new RegExp(token));
});
