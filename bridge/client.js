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

  if (legacyRaw && !lanRaw && !env.ANDROID_MCP_TAILSCALE_URL && env.ANDROID_MCP_TRANSPORT == null) {
    return { url: tailscaleUrl, token, preference: 'tailscale', lanUrl: null, tailscaleUrl, discovery: false };
  }
  if (preference === 'tailscale' && !tailscaleUrl) throw new Error('Tailscale transport requires ANDROID_MCP_TAILSCALE_URL or ANDROID_MCP_URL.');
  if (preference === 'lan' && !lanUrl && !discovery) throw new Error('LAN transport requires a LAN URL or discovery.');
  if (preference === 'auto' && !lanUrl && !tailscaleUrl && !discovery) throw new Error('No Android MCP transport configured.');
  return { token, preference, lanUrl, tailscaleUrl, discovery };
}

export class AndroidClient {
  constructor({ url = null, token, preference = 'auto', lanUrl = null, tailscaleUrl = null, discovery = true,
                timeoutMs = 30000, probeTimeoutMs = 1000, maxResponseBytes = 8 * 1024 * 1024,
                resolver = null, fetchImpl = fetch }) {
    this.url = url;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.probeTimeoutMs = probeTimeoutMs;
    this.maxResponseBytes = maxResponseBytes;
    this.fetch = fetchImpl;
    this.resolver = resolver;
    this.lanSessions = new Map();
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
        throw new Error(`Android HTTP ${response.status}`);
      }
      const reader = response.body.getReader();
      let size = 0;
      const chunks = [];
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > this.maxResponseBytes) throw new Error('Android response too large');
          chunks.push(value);
        }
      } finally { await reader.cancel(); }
      let payload;
      try { payload = JSON.parse(Buffer.concat(chunks, size).toString('utf8')); }
      catch { throw new Error(response.ok ? 'Android returned invalid JSON' : `Android HTTP ${response.status}`); }
      if (!payload || typeof payload !== 'object') throw new Error('Android returned invalid response');
      if (payload.error) {
        const code = typeof payload.error.code === 'string' && /^[A-Z_]{1,80}$/.test(payload.error.code) ? payload.error.code : 'REMOTE_ERROR';
        // Do not echo remote messages: they can include sensitive document paths or credentials.
        if (code === 'TIMEOUT') throw new Error('Android TIMEOUT: operation outcome unknown. Observe the current state before retrying.');
        throw new Error(`Android ${code}`);
      }
      if (!response.ok) throw new Error(`Android HTTP ${response.status}`);
      if (!Object.hasOwn(payload, 'result')) throw new Error('Android response has no result');
      return payload.result;
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
        this.lanSessions.delete(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      if (response.status === 401) {
        await response.body?.cancel();
        this.lanSessions.delete(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      let payload;
      try {
        payload = decryptLanResponse(this.token, session,
          await this.readJsonResponse(response, this.lanWireResponseLimit()));
      } catch {
        this.lanSessions.delete(url);
        throw this.lanUnauthenticatedResponseError(probe);
      }
      return this.unwrapPayload(response, payload);
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
    if (cached) return cached;
    const nonce = randomHelloNonce();
    const response = await this.fetch(new URL('hello', url), {
      method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(timeoutMs),
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ nonce }),
    });
    if (!response.ok) {
      await response.body?.cancel();
      const error = new Error('Android LAN server authentication failed');
      error.kind = response.status === 401 ? 'auth' : 'unreachable';
      throw error;
    }
    const hello = await this.readJsonResponse(response, 4096);
    const session = verifyHello(this.token, nonce, hello);
    if (!session) {
      const error = new Error('Android LAN server authentication failed');
      error.kind = 'auth';
      throw error;
    }
    this.lanSessions.set(url, session);
    return session;
  }

  async readJsonResponse(response, maxBytes) {
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

  unwrapPayload(response, payload) {
    if (!payload || typeof payload !== 'object') throw new Error('Android returned invalid response');
    if (payload.error) {
      const code = typeof payload.error.code === 'string' && /^[A-Z_]{1,80}$/.test(payload.error.code) ? payload.error.code : 'REMOTE_ERROR';
      if (code === 'TIMEOUT') throw new Error('Android TIMEOUT: operation outcome unknown. Observe the current state before retrying.');
      throw new Error(`Android ${code}`);
    }
    if (!response.ok) throw new Error(`Android HTTP ${response.status}`);
    if (!Object.hasOwn(payload, 'result')) throw new Error('Android response has no result');
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
