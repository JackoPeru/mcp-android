import { discoverLan } from './discovery.js';
import { TransportResolver, validateLanOrigin, validateTailscaleOrigin } from './transport.js';
import {
  MEDIA_TYPE as LAN_MEDIA_TYPE,
  decryptResponse as decryptLanResponse,
  encryptRequest as encryptLanRequest,
  randomHelloNonce,
  verifyHello,
} from './lan-channel.js';

export function readConfig(env = process.env) {
  const token = env.ANDROID_MCP_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('ANDROID_MCP_TOKEN must contain the 64 hex characters shown by the phone.');
  const preference = (env.ANDROID_MCP_TRANSPORT ?? 'auto').toLowerCase();
  if (!['auto', 'lan', 'tailscale'].includes(preference)) throw new Error('ANDROID_MCP_TRANSPORT must be auto, lan, or tailscale.');
  const discoveryRaw = (env.ANDROID_MCP_DISCOVERY ?? 'true').toLowerCase();
  if (!['true', 'false', '1', '0'].includes(discoveryRaw)) throw new Error('ANDROID_MCP_DISCOVERY must be true/false or 1/0.');
  const discovery = discoveryRaw === 'true' || discoveryRaw === '1';
  const legacyRaw = env.ANDROID_MCP_URL ?? '';
  const lanRaw = env.ANDROID_MCP_LAN_URL ?? '';
  const tailscaleRaw = env.ANDROID_MCP_TAILSCALE_URL ?? legacyRaw;
  const lanUrl = lanRaw ? validateLanOrigin(lanRaw) : null;
  const tailscaleUrl = tailscaleRaw ? validateTailscaleOrigin(tailscaleRaw) : null;

  if (legacyRaw && !lanRaw && !env.ANDROID_MCP_TAILSCALE_URL && env.ANDROID_MCP_TRANSPORT === undefined) {
    return { url: tailscaleUrl, token, preference: 'tailscale', lanUrl: null, tailscaleUrl, discovery: false };
  }
  if (preference === 'tailscale' && !tailscaleUrl) throw new Error('Tailscale transport requires ANDROID_MCP_TAILSCALE_URL or ANDROID_MCP_URL.');
  if (preference === 'lan' && !lanUrl && !discovery) throw new Error('LAN transport requires a LAN URL or discovery.');
  if (preference === 'auto' && !lanUrl && !tailscaleUrl && !discovery) throw new Error('No Android MCP transport configured.');
  return { token, preference, lanUrl, tailscaleUrl, discovery };
}

const LAN_SESSION_MAX_AGE_MS = 10 * 60 * 1000;

function isLoopbackTestOrigin(value) {
  // Intentionally without a port pin: the test harness binds ephemeral ports.
  // Loopback only, never routable, so a bearer sent here cannot leave the machine.
  try {
    const endpoint = new URL(value);
    return endpoint.protocol === 'http:' &&
      (endpoint.hostname === '127.0.0.1' || endpoint.hostname === '::1' || endpoint.hostname.toLowerCase() === 'localhost');
  } catch {
    return false;
  }
}

function validateDirectUrl(value) {
  // Programmatic use must not turn the bearer into an open redirector:
  // allow only validated LAN/Tailscale origins, plus loopback for tests.
  try { return validateLanOrigin(value); } catch { }
  try { return validateTailscaleOrigin(value); } catch { }
  if (isLoopbackTestOrigin(value)) return new URL(value).href;
  throw new Error('Direct client URL must be a validated LAN/Tailscale origin on port 8765.');
}

export class AndroidClient {
  constructor({ url = null, token, preference = 'auto', lanUrl = null, tailscaleUrl = null, discovery = true,
                timeoutMs = 30000, probeTimeoutMs = 1000, maxResponseBytes = 8 * 1024 * 1024,
                resolver = null, fetchImpl = fetch, now = () => Date.now() }) {
    this.url = url ? validateDirectUrl(url) : null;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.probeTimeoutMs = probeTimeoutMs;
    this.maxResponseBytes = maxResponseBytes;
    this.fetch = fetchImpl;
    this.now = now;
    this.resolver = resolver;
    this.lanSessions = new Map();
    this.lanSessionPromises = new Map();
    this.lanSessionEstablishedAt = new Map();
    if (!this.url && !this.resolver) {
      this.resolver = new TransportResolver({ preference, lanUrl, tailscaleUrl, discovery }, {
        discover: () => discoverLan({ token: this.token }),
        probe: endpoint => this.callOnce(endpoint, 'status', {}, true, this.probeTimeoutMs),
      });
    }
  }

  async call(method, params = {}) {
    const endpoint = this.url ? { url: this.url, transport: 'direct' } : await this.resolver.resolve();
    try {
      const result = await this.callOnce(endpoint.url, method, params, false);
      this.resolver?.noteSuccess?.(endpoint.url, endpoint.transport);
      return result;
    } catch (error) {
      this.resolver?.noteFailure?.(endpoint.url, endpoint.transport, error);
      throw error;
    }
  }

  async callOnce(url, method, params = {}, probe = false, timeoutMs = this.timeoutMs) {
    let isLan = false;
    try { validateLanOrigin(url); isLan = true; } catch { }
    if (isLan) return this.callLanOnce(url, method, params, probe, timeoutMs);
    return this.callBearerOnce(url, method, params, probe, timeoutMs);
  }

  bearerAmbiguousError(probe, message) {
    // Any HTTP/coding failure after a mutating POST leaves the outcome
    // ambiguous: the phone may have executed the action before the response
    // was lost, truncated or redirected. Never silently continue a batch.
    // Probes are read-only `status` checks, so map the same condition to
    // `unreachable` to allow Tailscale fallback instead of failing hard.
    const error = new Error(probe ? `${message} (endpoint unreachable)` : `${message}; operation outcome unknown.`);
    error.kind = probe ? 'unreachable' : 'outcome_unknown';
    return error;
  }

  async callBearerOnce(url, method, params = {}, probe = false, timeoutMs = this.timeoutMs) {
    let response;
    try {
      response = await this.fetch(new URL('rpc', url), {
        method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
        headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ method, params }),
      });
      if (response.status === 401) {
        await response.body?.cancel();
        const error = new Error('Android HTTP 401');
        error.kind = 'auth';
        throw error;
      }
      if (response.status >= 300 && response.status < 400) {
        await response.body?.cancel();
        // A redirect after POST is ambiguous (action may have executed):
        // never follow it, never replay, mark outcome unknown.
        throw this.bearerAmbiguousError(probe, `Android HTTP ${response.status}`);
      }
      let payload;
      try {
        payload = await this.readJsonResponse(response, this.maxResponseBytes);
      } catch (error) {
        if (error?.kind) throw error;
        throw this.bearerAmbiguousError(probe, error.message);
      }
      return this.unwrapPayload(response, payload, probe);
    } catch (error) {
      if (error.name === 'TimeoutError' || error.name === 'AbortError') {
        const wrapped = new Error(probe ? 'Android endpoint probe timed out.' : 'Android request timed out; operation outcome unknown. Inspect status before retrying gestures.');
        wrapped.kind = probe ? 'unreachable' : 'outcome_unknown';
        throw wrapped;
      }
      if (error.message === 'fetch failed') {
        const wrapped = new Error(probe ? 'Android endpoint unreachable.' : 'Android transport disconnected; operation outcome unknown.');
        wrapped.kind = probe ? 'unreachable' : 'outcome_unknown';
        throw wrapped;
      }
      throw error;
    }
  }

  async callLanOnce(url, method, params = {}, probe = false, timeoutMs = this.timeoutMs) {
    let response;
    try {
      const session = await this.ensureLanSession(url, Math.min(timeoutMs, this.probeTimeoutMs));
      const envelope = encryptLanRequest(this.token, session, { method, params });
      response = await this.fetch(new URL('rpc', url), {
        method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
        headers: { 'content-type': LAN_MEDIA_TYPE },
        body: JSON.stringify(envelope),
      });
      if (response.status >= 300 && response.status < 400) {
        await response.body?.cancel();
        this.invalidateLanSession(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      if (response.status === 401) {
        await response.body?.cancel();
        this.invalidateLanSession(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      let payload;
      try {
        payload = decryptLanResponse(this.token, session,
          await this.readJsonResponse(response, this.lanWireResponseLimit()));
        if (payload.requestNonce !== envelope.nonce) {
          throw new Error('LAN response is bound to a different request');
        }
      } catch (error) {
        // Decrypt/nonce failures are auth-level. Successfully decrypted but
        // malformed payloads (unwrapPayload outcome_unknown/unreachable) are
        // also ambiguous: invalidate the session so the next RPC re-hellos
        // with fresh keys instead of reusing a suspect session.
        if (error?.kind === 'outcome_unknown' || error?.kind === 'unreachable') {
          this.invalidateLanSession(url);
          throw error;
        }
        this.invalidateLanSession(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      try {
        return this.unwrapPayload(response, payload, probe);
      } catch (error) {
        if (error?.kind === 'outcome_unknown' || error?.kind === 'unreachable') this.invalidateLanSession(url);
        throw error;
      }
    } catch (error) {
      if (error.name === 'TimeoutError' || error.name === 'AbortError') {
        const wrapped = new Error(probe ? 'Android endpoint probe timed out.' : 'Android request timed out; operation outcome unknown. Inspect status before retrying gestures.');
        wrapped.kind = probe ? 'unreachable' : 'outcome_unknown';
        throw wrapped;
      }
      if (error.message === 'fetch failed') {
        const wrapped = new Error(probe ? 'Android endpoint unreachable.' : 'Android transport disconnected; operation outcome unknown.');
        wrapped.kind = probe ? 'unreachable' : 'outcome_unknown';
        throw wrapped;
      }
      throw error;
    }
  }

  async ensureLanSession(url, timeoutMs) {
    const cached = this.lanSessions.get(url);
    if (cached) {
      const establishedAt = this.lanSessionEstablishedAt.get(url) ?? 0;
      if (this.now() - establishedAt <= LAN_SESSION_MAX_AGE_MS) return cached;
      this.invalidateLanSession(url);
    }
    const pending = this.lanSessionPromises.get(url);
    if (pending) return pending;
    const promise = this.establishLanSession(url, timeoutMs);
    this.lanSessionPromises.set(url, promise);
    try {
      return await promise;
    } finally {
      if (this.lanSessionPromises.get(url) === promise) this.lanSessionPromises.delete(url);
    }
  }

  async establishLanSession(url, timeoutMs) {
    const nonce = randomHelloNonce();
    const response = await this.fetch(new URL('hello', url), {
      method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ nonce }),
    });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      const error = new Error('Android LAN server authentication failed');
      error.kind = 'unreachable';
      throw error;
    }
    if (!response.ok) {
      await response.body?.cancel();
      const error = new Error('Android LAN server authentication failed');
      error.kind = response.status === 401 ? 'auth' : 'unreachable';
      throw error;
    }
    let hello;
    try {
      hello = await this.readJsonResponse(response, 4096);
    } catch {
      const error = new Error('Android LAN server authentication failed');
      error.kind = 'unreachable';
      throw error;
    }
    const session = verifyHello(this.token, nonce, hello);
    if (!session) {
      const error = new Error('Android LAN server authentication failed');
      error.kind = 'auth';
      throw error;
    }
    this.lanSessions.set(url, session);
    this.lanSessionEstablishedAt.set(url, this.now());
    return session;
  }

  invalidateLanSession(url) {
    this.lanSessions.delete(url);
    this.lanSessionPromises.delete(url);
    this.lanSessionEstablishedAt.delete(url);
  }

  async readJsonResponse(response, maxBytes) {
    // A 204/empty body has no reader: after POST this is ambiguous, not success.
    if (!response.body) {
      const error = new Error(response.ok ? 'Android returned empty response' : `Android HTTP ${response.status}`);
      throw error;
    }
    const reader = response.body.getReader();
    let size = 0;
    const chunks = [];
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > maxBytes) throw new Error('Android response too large');
        chunks.push(value);
      }
    } finally { await reader.cancel(); }
    try { return JSON.parse(Buffer.concat(chunks, size).toString('utf8')); }
    catch { throw new Error(response.ok ? 'Android returned invalid JSON' : `Android HTTP ${response.status}`); }
  }

  unwrapPayload(response, payload, probe = false) {
    // Single shared RPC result parser (bearer + LAN): avoids drift between
    // two copies. Authenticated app errors (INVALID_ARGUMENT, …) stay
    // kind-less so a healthy endpoint is not evicted; every transport-level
    // ambiguity after POST becomes outcome_unknown (or unreachable in probe).
    if (!payload || typeof payload !== 'object') {
      throw this.bearerAmbiguousError(probe, 'Android returned invalid response');
    }
    if (payload.error) {
      const code = typeof payload.error.code === 'string' && /^[A-Z_]{1,80}$/.test(payload.error.code) ? payload.error.code : 'REMOTE_ERROR';
      // Do not echo remote messages: they can include sensitive document paths or credentials.
      if (code === 'TIMEOUT') {
        // Probes are read-only status checks: same ambiguity, unreachable kind
        // so the resolver falls back instead of failing hard.
        const error = new Error(probe
          ? 'Android endpoint probe timed out.'
          : 'Android TIMEOUT: operation outcome unknown. Observe the current state before retrying.');
        error.kind = probe ? 'unreachable' : 'outcome_unknown';
        throw error;
      }
      throw new Error(`Android ${code}`);
    }
    if (!response.ok) throw this.bearerAmbiguousError(probe, `Android HTTP ${response.status}`);
    if (!Object.hasOwn(payload, 'result')) throw this.bearerAmbiguousError(probe, 'Android response has no result');
    return payload.result;
  }

  lanWireResponseLimit() {
    return Math.ceil(this.maxResponseBytes * 4 / 3) + 4096;
  }

  lanUnauthenticatedResponseError(probe) {
    const error = new Error(probe
      ? 'Android LAN response authentication failed.'
      : 'Android LAN response authentication failed; operation outcome unknown.');
    error.kind = probe ? 'unreachable' : 'outcome_unknown';
    return error;
  }
}
