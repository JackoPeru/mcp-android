import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawn } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  relayAuthProof,
  relayOkProof,
  relaySessionId,
} from '../bridge/relay-client.js';
import {
  decryptRelayRequest,
  decryptRequest,
  encryptRelayRequest,
} from '../bridge/lan-channel.js';

// Shared cross-language vectors (asserted identically in RelayClientTest.java).
const TOKEN = 'a'.repeat(64);
const SECRET = 'b'.repeat(64);
const ROOM = 'room-12345';
const CLIENT_NONCE = '00112233445566778899aabbccddeeff';
const SERVER_NONCE = 'ffeeddccbbaa99887766554433221100';
const SESSION = '0123456789abcdef0123456789abcdef';

test('relay proofs and session match the fixed cross-language vectors', () => {
  assert.equal(relayAuthProof(SECRET, 'phone', ROOM, CLIENT_NONCE),
    '1901039f5b0da741202f897ddaa7e7bae25fa99f77b1aabe82e971afbf9da716');
  assert.equal(relayOkProof(SECRET, 'phone', ROOM, CLIENT_NONCE, SERVER_NONCE),
    '316e99f5430692fdca741a7909ac8991491ee6ae71b8ce2bd8c57d526ff15a96');
  assert.equal(relaySessionId(TOKEN, ROOM, CLIENT_NONCE, SERVER_NONCE),
    '2b650a91ed32394ade5e6ed32481c0c3');
  assert.doesNotMatch(relayAuthProof(SECRET, 'phone', ROOM, CLIENT_NONCE), new RegExp(TOKEN));
});

test('relay channel is domain-separated from LAN', () => {
  const envelope = encryptRelayRequest(TOKEN, SESSION, { method: 'status', params: {} });
  assert.deepEqual(decryptRelayRequest(TOKEN, SESSION, envelope), { method: 'status', params: {} });
  // A relay-domain request must not authenticate under the LAN domain.
  assert.throws(() => decryptRequest(TOKEN, SESSION, envelope));
});

test('relay server authenticates peers and forwards opaque frames', async t => {
  const port = 20000 + Math.floor(Math.random() * 20000);
  const script = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'relay', 'relay-server.mjs');
  const child = spawn(process.execPath, [script], {
    env: {
      ...process.env,
      RELAY_SECRET: SECRET,
      RELAY_ALLOW_PLAINTEXT: '1',
      RELAY_HOST: '127.0.0.1',
      RELAY_PORT: String(port),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => child.kill());
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('relay did not start')), 5000);
    child.stdout.on('data', chunk => {
      if (String(chunk).includes('listening')) { clearTimeout(timer); resolve(); }
    });
    child.on('exit', code => { clearTimeout(timer); reject(new Error(`relay exited ${code}`)); });
  });

  const connect = role => new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.write(JSON.stringify({
        type: 'hello', role, room: ROOM, nonce: CLIENT_NONCE,
        proof: relayAuthProof(SECRET, role, ROOM, CLIENT_NONCE),
      }) + '\n');
    });
    let buffer = '';
    socket.on('data', chunk => {
      buffer += chunk;
      const index = buffer.indexOf('\n');
      if (index < 0) return;
      const frame = JSON.parse(buffer.slice(0, index));
      if (frame.type !== 'hello_ok') { reject(new Error('handshake rejected')); return; }
      const expected = relayOkProof(SECRET, role, ROOM, CLIENT_NONCE, frame.nonce);
      if (frame.proof !== expected) { reject(new Error('bad server proof')); return; }
      resolve(socket);
    });
    socket.on('error', reject);
  });

  const phone = await connect('phone');
  const bridge = await connect('bridge');
  t.after(() => { phone.destroy(); bridge.destroy(); });
  const received = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('frame not forwarded')), 3000);
    bridge.on('data', chunk => {
      const line = String(chunk).split('\n')[0];
      const frame = JSON.parse(line);
      if (frame.type === 'data') { clearTimeout(timer); resolve(frame.payload); }
    });
    phone.write(JSON.stringify({ type: 'data', payload: 'b3BhcXVl' }) + '\n');
  });
  assert.equal(received, 'b3BhcXVl');
});

test('relay server rejects a wrong pairing secret', async t => {
  const port = 40000 + Math.floor(Math.random() * 20000);
  const script = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'relay', 'relay-server.mjs');
  const child = spawn(process.execPath, [script], {
    env: {
      ...process.env,
      RELAY_SECRET: SECRET,
      RELAY_ALLOW_PLAINTEXT: '1',
      RELAY_HOST: '127.0.0.1',
      RELAY_PORT: String(port),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => child.kill());
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('relay did not start')), 5000);
    child.stdout.on('data', chunk => {
      if (String(chunk).includes('listening')) { clearTimeout(timer); resolve(); }
    });
  });
  const closed = await new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1', () => {
      socket.write(JSON.stringify({
        type: 'hello', role: 'phone', room: ROOM, nonce: CLIENT_NONCE,
        proof: relayAuthProof('c'.repeat(64), 'phone', ROOM, CLIENT_NONCE),
      }) + '\n');
    });
    const timer = setTimeout(() => reject(new Error('relay did not drop the bad peer')), 3000);
    socket.on('close', () => { clearTimeout(timer); resolve(true); });
    socket.on('error', () => { clearTimeout(timer); resolve(true); });
  });
  assert.equal(closed, true);
});
