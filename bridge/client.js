import { isIP } from 'node:net';

export function readConfig(env = process.env) {
  const endpoint = new URL(env.ANDROID_MCP_URL ?? '');
  const octets = endpoint.hostname.split('.').map(Number);
  if (!['http:', 'https:'].includes(endpoint.protocol) || isIP(endpoint.hostname) !== 4 ||
      octets[0] !== 100 || octets[1] < 64 || octets[1] > 127 || endpoint.username ||
      endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash) {
    throw new Error('ANDROID_MCP_URL must be a Tailscale IPv4 HTTP(S) origin (100.64.0.0/10).');
  }
  const token = env.ANDROID_MCP_TOKEN ?? '';
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('ANDROID_MCP_TOKEN must contain the 64 hex characters shown by the phone.');
  return { url: endpoint.href, token };
}

export class AndroidClient {
  constructor({ url, token, timeoutMs = 30000, maxResponseBytes = 8 * 1024 * 1024 }) {
    this.url = url;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.maxResponseBytes = maxResponseBytes;
  }

  async call(method, params = {}) {
    let response;
    try {
      response = await fetch(new URL('rpc', this.url), {
        method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(this.timeoutMs),
        headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ method, params }),
      });
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
      if (error.name === 'TimeoutError' || error.name === 'AbortError') throw new Error('Android request timed out; operation outcome unknown. Inspect status before retrying gestures.');
      if (error.message === 'fetch failed') throw new Error('Android unreachable. Check Tailscale and start the remote service on the phone.');
      throw error;
    }
  }
}
