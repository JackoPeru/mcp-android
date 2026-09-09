import assert from 'node:assert/strict';
import test from 'node:test';
import {
  MEDIA_TYPE,
  decryptRequest,
  decryptResponse,
  encryptRequest,
  encryptResponse,
  helloProof,
  verifyHello,
} from '../bridge/lan-channel.js';
import { AndroidClient } from '../bridge/client.js';

const token = 'a'.repeat(64);
const session = '0123456789abcdef0123456789abcdef';

test('LAN channel matches the fixed cross-language AES-GCM vector', () => {
  const envelope = encryptRequest(
    token,
    session,
    { method: 'status', params: {} },
    '000102030405060708090a0b',
  );
  assert.equal(MEDIA_TYPE, 'application/mcp-android-lan+json');
  assert.deepEqual(envelope, {
    version: 1,
    session,
    nonce: '000102030405060708090a0b',
    ciphertext: 'LIOxGig8y1oNByNAujScLRmcbl9eG4vtBCKqDg339plq5pFDgNOfqVdhDBuE7+Y=',
  });
  assert.deepEqual(decryptRequest(token, session, envelope), { method: 'status', params: {} });
  assert.doesNotMatch(JSON.stringify(envelope), /status|params|aaaaaaaaaaaaaaaa/);
});

test('hello proof authenticates the server session without exposing the bearer', () => {
  const proof = helloProof(token, 'abc123', session);
  assert.equal(proof, '02f516369a95008435bff033aeb42c6d3424a57996a5132a37cb86b3386e57c4');
  const payload = {
    protocol: 'mcp-android-lan-channel',
    version: 1,
    nonce: 'abc123',
    session,
    proof,
  };
  assert.equal(verifyHello(token, 'abc123', payload), session);
  assert.equal(verifyHello(token, 'abc123', { ...payload, session: 'f'.repeat(32) }), null);
  assert.doesNotMatch(JSON.stringify(payload), new RegExp(token));
});

test('response authentication rejects modification and the wrong session', () => {
  const response = encryptResponse(
    token,
    session,
    { result: { ok: true } },
    '0c0d0e0f1011121314151617',
  );
  assert.deepEqual(decryptResponse(token, session, response), { result: { ok: true } });
  assert.throws(() => decryptResponse(token, 'f'.repeat(32), response));
  const tampered = { ...response, ciphertext: response.ciphertext.slice(0, -2) + 'AA' };
  assert.throws(() => decryptResponse(token, session, tampered));
});

test('LAN client never sends the bearer or plaintext RPC and verifies server before RPC', async () => {
  const seen = [];
  const fetchImpl = async (url, options) => {
    seen.push({ url: url.pathname, headers: options.headers, body: options.body });
    assert.equal(options.headers.authorization, undefined);
    assert.doesNotMatch(options.body, new RegExp(token));
    if (url.pathname === '/hello') {
      const request = JSON.parse(options.body);
      return new Response(JSON.stringify({
        protocol: 'mcp-android-lan-channel', version: 1, nonce: request.nonce, session,
        proof: helloProof(token, request.nonce, session),
      }), { status: 200, headers: { 'content-type': 'application/json' } });
    }
    assert.equal(url.pathname, '/rpc');
    assert.doesNotMatch(options.body, /status|params/);
    const request = decryptRequest(token, session, JSON.parse(options.body));
    assert.deepEqual(request, { method: 'status', params: {} });
    return new Response(JSON.stringify(encryptResponse(token, session, { result: { ok: true } })), {
      status: 200,
      headers: { 'content-type': MEDIA_TYPE },
    });
  };
  const client = new AndroidClient({
    token,
    resolver: { resolve: async () => ({ url: 'http://192.168.1.84:8765/', transport: 'lan' }) },
    fetchImpl,
  });
  assert.deepEqual(await client.call('status', {}), { ok: true });
  assert.deepEqual(seen.map(entry => entry.url), ['/hello', '/rpc']);
});

test('tampered LAN response is outcome-unknown and invalidates the cached session', async () => {
  const url = 'http://192.168.1.84:8765/';
  const fetchImpl = async (target, options) => {
    if (target.pathname === '/hello') {
      const request = JSON.parse(options.body);
      return new Response(JSON.stringify({
        protocol: 'mcp-android-lan-channel', version: 1, nonce: request.nonce, session,
        proof: helloProof(token, request.nonce, session),
      }), { status: 200 });
    }
    const response = encryptResponse(token, session, { result: { ok: true } });
    response.ciphertext = response.ciphertext.slice(0, -2) + 'AA';
    return new Response(JSON.stringify(response), { status: 200 });
  };
  const client = new AndroidClient({
    token,
    resolver: { resolve: async () => ({ url, transport: 'lan' }) },
    fetchImpl,
  });
  await assert.rejects(client.call('tap', { x: 1, y: 1 }), error => {
    assert.equal(error.kind, 'outcome_unknown');
    assert.match(error.message, /outcome unknown/i);
    return true;
  });
  assert.equal(client.lanSessions.has(url), false);
});
