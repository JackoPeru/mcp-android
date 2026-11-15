#!/usr/bin/env node
// Self-hosted relay for MCP Android: the phone and the bridge both dial out
// over TLS, the relay matches them by room and forwards opaque frames.
//
// The relay NEVER receives the MCP token: authentication uses a separate
// pairing secret, and RPC payloads are end-to-end encrypted between the phone
// and the bridge (AES-256-GCM under a relay-specific domain). What the relay
// sees is TLS plus ciphertext.
//
// Env:
//   RELAY_SECRET      64 hex chars, shared with phone and bridge (required)
//   RELAY_TLS_CERT    PEM certificate path (required unless loopback+plaintext)
//   RELAY_TLS_KEY     PEM key path (required unless loopback+plaintext)
//   RELAY_HOST        bind host, default 127.0.0.1
//   RELAY_PORT        bind port, default 9443
//   RELAY_ALLOW_PLAINTEXT=1   only allowed for loopback binds
//
// Usage: node relay/relay-server.mjs
import tls from 'node:tls';
import net from 'node:net';
import fs from 'node:fs';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

const SECRET = (process.env.RELAY_SECRET ?? '').trim().toLowerCase();
const HOST = process.env.RELAY_HOST ?? '127.0.0.1';
const PORT = Number(process.env.RELAY_PORT ?? 9443);
const CERT = process.env.RELAY_TLS_CERT ?? '';
const KEY = process.env.RELAY_TLS_KEY ?? '';
const ALLOW_PLAINTEXT = process.env.RELAY_ALLOW_PLAINTEXT === '1';
const MAX_LINE = 16 * 1024 * 1024;
const PING_MS = 30_000;
const IDLE_MS = 120_000;

function fail(message) {
  console.error(`relay: ${message}`);
  process.exitCode = 1;
  process.exit(1);
}

if (!/^[a-f0-9]{64}$/.test(SECRET)) fail('RELAY_SECRET must be 64 hex characters');
const loopback = HOST === '127.0.0.1' || HOST === '::1' || HOST === 'localhost';
if (!loopback && ALLOW_PLAINTEXT) fail('plaintext relay is only allowed on loopback');
if (!loopback) {
  console.error('relay: binding a non-loopback address requires TLS (RELAY_TLS_CERT/KEY)');
}
if (!CERT || !KEY) {
  if (!ALLOW_PLAINTEXT || !loopback) {
    fail('TLS is mandatory for non-loopback binds: set RELAY_TLS_CERT and RELAY_TLS_KEY');
  }
}

const authProof = (role, room, nonce) =>
  createHmac('sha256', Buffer.from(SECRET, 'hex'))
    .update(`mcp-android-relay-auth-v1\n${role}\n${room}\n${nonce}`, 'utf8').digest('hex');
const okProof = (role, room, clientNonce, serverNonce) =>
  createHmac('sha256', Buffer.from(SECRET, 'hex'))
    .update(`mcp-android-relay-auth-v1\nok\n${role}\n${room}\n${clientNonce}\n${serverNonce}`, 'utf8')
    .digest('hex');

const rooms = new Map(); // room -> { phone, bridge }

function safeEqualHex(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  const left = Buffer.from(a, 'hex');
  const right = Buffer.from(b, 'hex');
  return left.length === right.length && timingSafeEqual(left, right);
}

function peerOf(room, role) {
  const entry = rooms.get(room);
  if (!entry) return null;
  return role === 'phone' ? entry.bridge : entry.phone;
}

function drop(connection) {
  const entry = rooms.get(connection.room);
  if (entry && entry[connection.role] === connection) entry[connection.role] = null;
  if (entry && !entry.phone && !entry.bridge) rooms.delete(connection.room);
  try { connection.socket.destroy(); } catch { /* ignore */ }
}

function attach(socket, label) {
  const connection = {
    socket,
    role: null,
    room: null,
    buffer: '',
    lastSeen: Date.now(),
    pingTimer: null,
  };
  socket.setEncoding('utf8');
  socket.setTimeout(15_000, () => {
    if (!connection.role) drop(connection);
  });
  socket.on('data', chunk => {
    connection.lastSeen = Date.now();
    connection.buffer += chunk;
    if (connection.buffer.length > MAX_LINE) {
      drop(connection);
      return;
    }
    let index;
    while ((index = connection.buffer.indexOf('\n')) >= 0) {
      const line = connection.buffer.slice(0, index);
      connection.buffer = connection.buffer.slice(index + 1);
      let frame;
      try { frame = JSON.parse(line); } catch { continue; }
      handle(connection, frame);
    }
  });
  socket.on('error', () => drop(connection));
  socket.on('close', () => drop(connection));
  socket.on('timeout', () => { /* handshake timeout handled above */ });
  console.log(`relay: ${label} connected`);
}

function handle(connection, frame) {
  if (!connection.role) {
    if (frame.type !== 'hello') { drop(connection); return; }
    const role = String(frame.role ?? '');
    const room = String(frame.room ?? '');
    const nonce = String(frame.nonce ?? '');
    if (!['phone', 'bridge'].includes(role)) { drop(connection); return; }
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(room)) { drop(connection); return; }
    if (!/^[a-f0-9]{32,64}$/.test(nonce)) { drop(connection); return; }
    if (!safeEqualHex(String(frame.proof ?? ''), authProof(role, room, nonce))) {
      console.log(`relay: ${role} auth rejected in ${room}`);
      drop(connection);
      return;
    }
    const serverNonce = randomBytes(16).toString('hex');
    connection.socket.write(JSON.stringify({
      type: 'hello_ok', nonce: serverNonce, proof: okProof(role, room, nonce, serverNonce),
    }) + '\n');
    const entry = rooms.get(room) ?? { phone: null, bridge: null };
    // One connection per role per room: a reconnect replaces the stale one.
    const previous = entry[role];
    if (previous) drop(previous);
    entry[role] = connection;
    rooms.set(room, entry);
    connection.role = role;
    connection.room = room;
    connection.socket.setTimeout(0);
    connection.socket.setKeepAlive(true, 30_000);
    connection.pingTimer = setInterval(() => {
      if (Date.now() - connection.lastSeen > IDLE_MS) { drop(connection); return; }
      try { connection.socket.write(JSON.stringify({ type: 'ping' }) + '\n'); } catch { drop(connection); }
    }, PING_MS);
    console.log(`relay: ${role} joined ${room}`);
    return;
  }
  if (frame.type === 'pong') return;
  if (frame.type !== 'data') return;
  const peer = peerOf(connection.room, connection.role);
  if (!peer) return;
  try {
    peer.socket.write(JSON.stringify({ type: 'data', payload: String(frame.payload ?? '') }) + '\n');
  } catch {
    drop(peer);
  }
}

const server = CERT && KEY
  ? tls.createServer({ cert: fs.readFileSync(CERT), key: fs.readFileSync(KEY) },
    socket => attach(socket, 'client'))
  : net.createServer(socket => attach(socket, 'client'));

server.on('error', error => fail(error.message));
server.listen(PORT, HOST, () => {
  console.log(`relay: listening on ${CERT && KEY ? 'tls' : 'tcp'}://${HOST}:${PORT}`);
});
