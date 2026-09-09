import { createCipheriv, createDecipheriv, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export const MEDIA_TYPE = 'application/mcp-android-lan+json';
export const PROTOCOL = 'mcp-android-lan-channel';
export const VERSION = 1;
const DOMAIN = 'mcp-android-lan-channel-v1';
const TOKEN_RE = /^[a-f0-9]{64}$/;
const SESSION_RE = /^[a-f0-9]{32}$/;
const NONCE_RE = /^[a-f0-9]{24}$/;
const PROOF_RE = /^[a-f0-9]{64}$/;

function tokenBytes(token) {
  if (!TOKEN_RE.test(token ?? '')) throw new Error('Valid LAN channel token required');
  return Buffer.from(token, 'hex');
}

function sessionValue(session) {
  if (!SESSION_RE.test(session ?? '')) throw new Error('Valid LAN channel session required');
  return session;
}

function deriveKey(token, session, direction) {
  if (direction !== 'request' && direction !== 'response') throw new Error('Invalid LAN channel direction');
  return createHmac('sha256', tokenBytes(token))
    .update(DOMAIN + '\n' + sessionValue(session) + '\n' + direction, 'utf8').digest();
}

function aad(direction, session) {
  if (direction !== 'request' && direction !== 'response') throw new Error('Invalid LAN channel direction');
  return Buffer.from(DOMAIN + '\n' + direction + '\n' + sessionValue(session), 'utf8');
}

function encrypt(token, session, direction, payload, nonceHex = randomBytes(12).toString('hex')) {
  if (!NONCE_RE.test(nonceHex)) throw new Error('Invalid LAN channel nonce');
  const cipher = createCipheriv('aes-256-gcm', deriveKey(token, session, direction), Buffer.from(nonceHex, 'hex'));
  cipher.setAAD(aad(direction, session));
  const plaintext = Buffer.from(JSON.stringify(payload), 'utf8');
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
  return { version: VERSION, session, nonce: nonceHex, ciphertext: ciphertext.toString('base64') };
}

function decrypt(token, session, direction, envelope) {
  if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) throw new Error('Invalid LAN channel envelope');
  const keys = Object.keys(envelope).sort();
  const expected = ['ciphertext', 'nonce', 'session', 'version'];
  if (keys.length !== expected.length || keys.some((key, index) => key !== expected[index])) throw new Error('Invalid LAN channel envelope');
  if (envelope.version !== VERSION || envelope.session !== session || !NONCE_RE.test(envelope.nonce ?? '') ||
      typeof envelope.ciphertext !== 'string' || envelope.ciphertext.length === 0 || envelope.ciphertext.length > 12 * 1024 * 1024) {
    throw new Error('Invalid LAN channel envelope');
  }
  const encrypted = Buffer.from(envelope.ciphertext, 'base64');
  if (encrypted.length < 17) throw new Error('Invalid LAN channel envelope');
  const decipher = createDecipheriv('aes-256-gcm', deriveKey(token, session, direction), Buffer.from(envelope.nonce, 'hex'));
  decipher.setAAD(aad(direction, session));
  decipher.setAuthTag(encrypted.subarray(encrypted.length - 16));
  const plaintext = Buffer.concat([decipher.update(encrypted.subarray(0, -16)), decipher.final()]);
  let payload;
  try { payload = JSON.parse(plaintext.toString('utf8')); } catch { throw new Error('Invalid LAN channel plaintext'); }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('Invalid LAN channel plaintext');
  return payload;
}

export function encryptRequest(token, session, payload, nonceHex) { return encrypt(token, session, 'request', payload, nonceHex); }
export function decryptRequest(token, session, envelope) { return decrypt(token, session, 'request', envelope); }
export function encryptResponse(token, session, payload, nonceHex) { return encrypt(token, session, 'response', payload, nonceHex); }
export function decryptResponse(token, session, envelope) { return decrypt(token, session, 'response', envelope); }

export function helloProof(token, nonce, session) {
  if (typeof nonce !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(nonce)) throw new Error('Invalid LAN hello nonce');
  return createHmac('sha256', tokenBytes(token))
    .update(DOMAIN + '\nhello\n' + nonce + '\n' + sessionValue(session), 'utf8').digest('hex');
}

export function verifyHello(token, nonce, payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  const keys = Object.keys(payload).sort();
  const expectedKeys = ['nonce', 'proof', 'protocol', 'session', 'version'];
  if (keys.length !== expectedKeys.length || keys.some((key, index) => key !== expectedKeys[index])) return null;
  if (payload.protocol !== PROTOCOL || payload.version !== VERSION || payload.nonce !== nonce ||
      !SESSION_RE.test(payload.session ?? '') || !PROOF_RE.test(payload.proof ?? '')) return null;
  let expected;
  try { expected = Buffer.from(helloProof(token, nonce, payload.session), 'hex'); } catch { return null; }
  const actual = Buffer.from(payload.proof, 'hex');
  return actual.length === expected.length && timingSafeEqual(actual, expected) ? payload.session : null;
}

export function randomHelloNonce() { return randomBytes(16).toString('hex'); }
