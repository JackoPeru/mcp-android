import dgram from 'node:dgram';
import os from 'node:os';
import { randomBytes } from 'node:crypto';
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

export function validateDiscoveryResponse(message, nonce, sourceAddress, targets) {
  if (message.length === 0 || message.length > 512) return null;
  let payload;
  try { payload = JSON.parse(message.toString('utf8')); } catch { return null; }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  const keys = Object.keys(payload).sort();
  const expected = ['address', 'nonce', 'port', 'protocol', 'transport', 'version'].sort();
  if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index])) return null;
  if (payload.protocol !== PROTOCOL || payload.version !== VERSION || payload.nonce !== nonce ||
      payload.transport !== 'lan' || payload.port !== RPC_PORT || typeof payload.address !== 'string') return null;
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
  try { return validateLanOrigin(`http://${payload.address}:${payload.port}`); }
  catch { return null; }
}

export async function discoverLan({ timeoutMs = 350, port = DISCOVERY_PORT, targets = defaultTargets() } = {}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs < 20 || timeoutMs > 1000) throw new Error('Invalid discovery timeout');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid discovery port');
  if (!Array.isArray(targets) || targets.length === 0) return [];
  const nonce = randomBytes(16).toString('hex');
  const request = Buffer.from(JSON.stringify({ protocol: PROTOCOL, version: VERSION, nonce }));
  const socket = dgram.createSocket('udp4');
  const candidates = new Set();
  try {
    await new Promise((resolve, reject) => {
      let settled = false;
      const finish = error => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        error ? reject(error) : resolve();
      };
      const timer = setTimeout(() => finish(), timeoutMs);
      socket.on('message', (message, remote) => {
        const candidate = validateDiscoveryResponse(message, nonce, remote.address, targets);
        if (candidate) candidates.add(candidate);
      });
      socket.once('error', error => finish(error));
      socket.bind(0, () => {
        try { socket.setBroadcast(true); } catch (error) { finish(error); return; }
        for (const target of targets) {
          if (!target || typeof target.broadcast !== 'string') continue;
          socket.send(request, port, target.broadcast, error => {
            if (error && !settled) finish(error);
          });
        }
      });
    });
    return [...candidates];
  } finally {
    socket.close();
  }
}
