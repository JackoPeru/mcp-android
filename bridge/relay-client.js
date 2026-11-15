import tls from 'node:tls';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { encryptRelayRequest, decryptRelayResponse } from './lan-channel.js';

// E2E relay client: the relay only sees opaque base64 envelopes. TLS protects
// the hop, the MCP token protects the content under a relay-specific domain.
//
// Safety model matches the LAN transport: one attempt per RPC, no automatic
// replay. A lost connection or timeout surfaces as outcome_unknown so a
// mutating action is never executed twice.

const RELAY_DOMAIN = 'mcp-android-relay-v1';
const MAX_LINE = 16 * 1024 * 1024;

function tokenBytes(token) {
  if (!/^[a-f0-9]{64}$/.test(token ?? '')) throw new Error('Valid relay token required');
  return Buffer.from(token, 'hex');
}

function hmacHex(hexKey, material) {
  return createHmac('sha256', Buffer.from(hexKey, 'hex')).update(material, 'utf8').digest('hex');
}

export function relayAuthProof(secretHex, role, room, nonce) {
  return hmacHex(secretHex, `mcp-android-relay-auth-v1\n${role}\n${room}\n${nonce}`);
}

export function relayOkProof(secretHex, role, room, clientNonce, serverNonce) {
  return hmacHex(secretHex,
    `mcp-android-relay-auth-v1\nok\n${role}\n${room}\n${clientNonce}\n${serverNonce}`);
}

/** Same derivation as RelayClient.java: 32 hex chars. */
export function relaySessionId(token, room, clientNonce, serverNonce) {
  return hmacHex(token, `mcp-android-relay-session-v1\n${room}\n${clientNonce}\n${serverNonce}`).slice(0, 32);
}

export class RelayClient {
  constructor({ url, room, secret, token, ca = null, timeoutMs = 30000 }) {
    const match = /^tls:\/\/([A-Za-z0-9.-]{1,253}):([0-9]{1,5})$/.exec(url ?? '');
    if (!match) throw new Error('Relay URL must be tls://host:port');
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(room ?? '')) throw new Error('Invalid relay room');
    if (!/^[a-f0-9]{64}$/.test(secret ?? '')) throw new Error('Invalid relay pairing secret');
    this.host = match[1];
    this.port = Number(match[2]);
    this.room = room;
    this.secret = secret;
    this.token = token;
    this.ca = ca;
    this.timeoutMs = timeoutMs;
    this.socket = null;
    this.session = null;
    this.buffer = '';
    this.waiters = new Map();
    this.connecting = null;
    this.closed = false;
    // Responses carry no request id on the wire beyond the nonce, and the
    // relay forwards in order; serialising calls keeps request/response
    // pairing exact without ever resending a mutating action.
    this.chain = Promise.resolve();
  }

  async ensure() {
    if (this.socket && this.session) return;
    if (this.connecting) return this.connecting;
    this.connecting = this.connectOnce()
      .then(() => { this.connecting = null; })
      .catch(error => { this.connecting = null; throw error; });
    return this.connecting;
  }

  connectOnce() {
    return new Promise((resolve, reject) => {
      const options = {
        host: this.host,
        port: this.port,
        servername: this.host,
        rejectUnauthorized: true,
      };
      if (this.ca) options.ca = this.ca;
      const socket = tls.connect(options, () => {});
      this.socket = socket;
      let settled = false;
      const fail = error => {
        if (settled) return;
        settled = true;
        this.teardown();
        reject(error);
      };
      socket.setEncoding('utf8');
      socket.setTimeout(this.timeoutMs, () => fail(new Error('relay handshake timeout')));
      socket.on('secureConnect', () => {
        const nonce = randomBytes(16).toString('hex');
        this.clientNonce = nonce;
        socket.write(JSON.stringify({
          type: 'hello', role: 'bridge', room: this.room, nonce,
          proof: relayAuthProof(this.secret, 'bridge', this.room, nonce),
        }) + '\n');
      });
      socket.on('data', chunk => {
        this.buffer += chunk;
        if (this.buffer.length > MAX_LINE) { fail(new Error('relay frame too large')); return; }
        let index;
        while ((index = this.buffer.indexOf('\n')) >= 0) {
          const line = this.buffer.slice(0, index);
          this.buffer = this.buffer.slice(index + 1);
          let frame;
          try { frame = JSON.parse(line); } catch { continue; }
          if (!settled) {
            if (frame.type !== 'hello_ok') { fail(new Error('relay authentication rejected')); return; }
            const expected = relayOkProof(this.secret, 'bridge', this.room,
              this.clientNonce, String(frame.nonce ?? ''));
            const actual = String(frame.proof ?? '');
            if (actual.length !== expected.length ||
                !timingSafeEqual(Buffer.from(actual, 'hex'), Buffer.from(expected, 'hex'))) {
              fail(new Error('relay did not prove the pairing secret'));
              return;
            }
            this.session = relaySessionId(this.token, this.room, this.clientNonce, String(frame.nonce ?? ''));
            settled = true;
            socket.setTimeout(0);
            socket.setKeepAlive(true, 30000);
            resolve();
            continue;
          }
          this.handle(frame);
        }
      });
      socket.on('error', error => {
        if (!settled) fail(error);
        else this.teardown(new Error('relay connection error'));
      });
      socket.on('close', () => {
        if (!settled) fail(new Error('relay closed during handshake'));
        else this.teardown(new Error('relay connection closed'));
      });
    });
  }

  handle(frame) {
    if (frame.type === 'ping') {
      this.write({ type: 'pong' });
      return;
    }
    if (frame.type !== 'data') return;
    const waiter = this.waiters.values().next().value;
    if (!waiter) return;
    this.waiters.delete(waiter.nonce);
    waiter.resolve(String(frame.payload ?? ''));
  }

  write(frame) {
    if (!this.socket || this.socket.destroyed) throw new Error('relay not connected');
    this.socket.write(JSON.stringify(frame) + '\n');
  }

  teardown(error) {
    this.session = null;
    if (this.socket) {
      try { this.socket.destroy(); } catch { /* ignore */ }
      this.socket = null;
    }
    for (const waiter of this.waiters.values()) {
      clearTimeout(waiter.timer);
      waiter.reject(error ?? new Error('relay disconnected'));
    }
    this.waiters.clear();
  }

  call(method, params = {}) {
    const run = this.chain.then(() => this.callOnce(method, params), () => this.callOnce(method, params));
    // Keep the chain alive regardless of individual failures.
    this.chain = run.catch(() => {});
    return run;
  }

  async callOnce(method, params = {}) {
    await this.ensure();
    const envelope = encryptRelayRequest(this.token, this.session, { method, params });
    const payload = Buffer.from(JSON.stringify(envelope), 'utf8').toString('base64');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(envelope.nonce);
        this.teardown(new Error('relay request timeout'));
        reject(Object.assign(new Error('Android relay request timed out; operation outcome unknown.'), {
          kind: 'outcome_unknown',
        }));
      }, this.timeoutMs);
      this.waiters.set(envelope.nonce, {
        nonce: envelope.nonce,
        timer,
        resolve: async raw => {
          clearTimeout(timer);
          try {
            const decoded = decryptRelayResponse(this.token, this.session,
              JSON.parse(Buffer.from(raw, 'base64').toString('utf8')));
            if (decoded.requestNonce !== envelope.nonce) {
              throw new Error('relay response bound to a different request');
            }
            resolve(decoded);
          } catch (error) {
            reject(Object.assign(new Error('Android relay response authentication failed; operation outcome unknown.'), {
              kind: 'outcome_unknown',
            }));
          }
        },
        reject,
      });
      try {
        this.write({ type: 'data', payload });
      } catch (error) {
        clearTimeout(timer);
        this.waiters.delete(envelope.nonce);
        reject(Object.assign(new Error('Android relay disconnected; operation outcome unknown.'), {
          kind: 'outcome_unknown',
        }));
      }
    });
  }

  close() {
    this.closed = true;
    this.teardown();
  }
}
