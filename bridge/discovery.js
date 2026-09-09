import dgram from 'node:dgram';
import os from 'node:os';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { isIP } from 'node:net';
import { isRfc1918, validateLanOrigin } from './transport.js';

const PROTOCOL = 'mcp-android-discovery';
const VERSION = 1;
const RPC_PORT = 8765;
const DISCOVERY_PORT = 8766;

function ipToInt(ip) {
  return ip.split('.').reduce((value, part) => ((value << 8) | Number(part)) >>> 0, 0) >>> 0;
}

function intToIp(value) {
  return `${value >>> 24 & 255}.${value >>> 16 & 255}.${value >>> 8 & 255}.${value & 255}`;
}

function defaultTargets() {
  const targets = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries ?? []) {
      if (entry.internal || entry.family !== 'IPv4') continue;
      try { if (!isRfc1918(entry.address)) continue; } catch { continue; }
      const address = ipToInt(entry.address);
      const mask = ipToInt(entry.netmask);
      const broadcast = (address | (~mask >>> 0)) >>> 0;
      targets.push({ address: entry.address, netmask: entry.netmask, broadcast: intToIp(broadcast) });
    }
  }
  return targets;
}

function sameSubnet(address, localAddress, netmask) {
  try {
    const mask = ipToInt(netmask);
    return ((ipToInt(address) & mask) >>> 0) === ((ipToInt(localAddress) & mask) >>> 0);
  } catch {
    return false;
  }
}

function proofMaterial(fields) {
  return [
    PROTOCOL,
    String(VERSION),
    fields.nonce,
    fields.address,
    String(fields.port),
    'lan',
  ].join('\n');
}

export function discoveryProof(token, fields) {
  if (!/^[a-f0-9]{64}$/.test(token ?? '')) throw new Error('Valid discovery token required');
  if (!fields || fields.protocol !== PROTOCOL || fields.version !== VERSION ||
      typeof fields.nonce !== 'string' || typeof fields.address !== 'string' ||
      fields.port !== RPC_PORT || fields.transport !== 'lan') {
    throw new Error('Invalid discovery proof fields');
  }
  return createHmac('sha256', Buffer.from(token, 'hex'))
    .update(proofMaterial(fields), 'utf8').digest('hex');
}

export function validateDiscoveryResponse(message, nonce, token, sourceAddress, targets) {
  if (message.length === 0 || message.length > 512) return null;
  let payload;
  try { payload = JSON.parse(message.toString('utf8')); } catch { return null; }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  const keys = Object.keys(payload).sort();
  const expected = ['address', 'nonce', 'port', 'proof', 'protocol', 'transport', 'version'].sort();
  if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index])) return null;
  if (payload.protocol !== PROTOCOL || payload.version !== VERSION || payload.nonce !== nonce ||
      payload.transport !== 'lan' || payload.port !== RPC_PORT || typeof payload.address !== 'string' ||
      typeof payload.proof !== 'string' || !/^[a-f0-9]{64}$/.test(payload.proof)) return null;
  if (payload.address !== sourceAddress) return null;
  try {
    if (!isRfc1918(sourceAddress)) return null;
  } catch {
    return null;
  }
  const onQueriedSubnet = targets.some(target =>
    target && typeof target.address === 'string' && typeof target.netmask === 'string' &&
    sameSubnet(sourceAddress, target.address, target.netmask));
  if (!onQueriedSubnet) return null;
  let expectedProof;
  try { expectedProof = discoveryProof(token, payload); } catch { return null; }
  const actual = Buffer.from(payload.proof, 'hex');
  const expectedBytes = Buffer.from(expectedProof, 'hex');
  if (actual.length !== expectedBytes.length || !timingSafeEqual(actual, expectedBytes)) return null;
  try { return validateLanOrigin('http://' + payload.address + ':' + payload.port); }
  catch { return null; }
}

export async function discoverLan({
  timeoutMs = 350, port = DISCOVERY_PORT, targets = defaultTargets(), token,
} = {}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs < 20 || timeoutMs > 1000) throw new Error('Invalid discovery timeout');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid discovery port');
  if (!/^[a-f0-9]{64}$/.test(token ?? '')) throw new Error('Valid discovery token required');
  if (!Array.isArray(targets) || targets.length === 0) return [];
  const nonce = randomBytes(16).toString('hex');
  const request = Buffer.from(JSON.stringify({ protocol: PROTOCOL, version: VERSION, nonce }));
  const socket = dgram.createSocket('udp4');
  const candidates = new Set();
  try {
    await new Promise((resolve, reject) => {
      let settled = false;
      let bound = false;
      const finish = error => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        error ? reject(error) : resolve();
      };
      const timer = setTimeout(() => finish(), timeoutMs);
      socket.on('message', (message, remote) => {
        const candidate = validateDiscoveryResponse(message, nonce, token, remote.address, targets);
        if (candidate) candidates.add(candidate);
      });
      socket.on('error', error => {
        // Binding/setup errors are fatal. Per-interface send failures after bind
        // are expected on PCs with stale/virtual adapters and must not abort
        // discovery on healthy interfaces.
        if (!bound) finish(error);
      });
      socket.bind(0, () => {
        bound = true;
        try { socket.setBroadcast(true); } catch (error) { finish(error); return; }
        for (const target of targets) {
          if (!target || typeof target.broadcast !== 'string' || isIP(target.broadcast) !== 4) continue;
          socket.send(request, port, target.broadcast, () => {});
        }
      });
    });
    return [...candidates];
  } finally {
    socket.close();
  }
}
