import { isIP } from 'node:net';

function ipv4(ip) {
  if (isIP(ip) !== 4) throw new Error('Numeric IPv4 required');
  return ip.split('.').reduce((value, part) => ((value << 8) | Number(part)) >>> 0, 0) >>> 0;
}

export function isRfc1918(ip) {
  const value = ipv4(ip);
  return ((value & 0xff000000) >>> 0) === 0x0a000000 ||
    ((value & 0xfff00000) >>> 0) === 0xac100000 ||
    ((value & 0xffff0000) >>> 0) === 0xc0a80000;
}

export function isTailscale(ip) {
  return ((ipv4(ip) & 0xffc00000) >>> 0) === 0x64400000;
}

function validateOrigin(value, predicate, label) {
  const endpoint = new URL(value ?? '');
  if (!['http:', 'https:'].includes(endpoint.protocol) || isIP(endpoint.hostname) !== 4 ||
      endpoint.username || endpoint.password || endpoint.pathname !== '/' || endpoint.search || endpoint.hash ||
      !predicate(endpoint.hostname)) {
    throw new Error(`${label} must be a numeric private IPv4 HTTP(S) origin without credentials, path, query, or fragment.`);
  }
  return endpoint.href;
}

export function validateLanOrigin(value) {
  return validateOrigin(value, isRfc1918, 'LAN endpoint');
}

export function validateTailscaleOrigin(value) {
  return validateOrigin(value, isTailscale, 'Tailscale endpoint');
}

export class TransportResolver {
  constructor(config, {
    discover,
    probe,
    now = () => Date.now(),
    validationTtlMs = 5_000,
    lanRetryMs = 5_000,
  }) {
    this.preference = config.preference ?? 'auto';
    this.configuredLanUrl = config.lanUrl ?? null;
    this.cachedLanUrl = this.configuredLanUrl;
    this.cachedLanSource = this.configuredLanUrl ? 'configured' : null;
    this.validatedLanAt = 0;
    this.lanRetryAfter = 0;
    this.tailscaleUrl = config.tailscaleUrl ?? null;
    this.discovery = config.discovery !== false;
    this.discover = discover;
    this.probe = probe;
    this.now = now;
    this.validationTtlMs = validationTtlMs;
    this.lanRetryMs = lanRetryMs;
  }

  async resolve() {
    if (this.preference === 'tailscale') {
      if (!this.tailscaleUrl) throw new Error('Tailscale endpoint unavailable');
      return { url: this.tailscaleUrl, transport: 'tailscale' };
    }

    const lan = await this.resolveLan();
    if (lan) return { url: lan, transport: 'lan' };

    if (this.preference === 'lan') throw new Error('LAN endpoint unavailable');
    if (this.tailscaleUrl) return { url: this.tailscaleUrl, transport: 'tailscale' };
    throw new Error('No Android MCP endpoint available');
  }

  async resolveLan() {
    const now = this.now();
    if (!this.cachedLanUrl && this.configuredLanUrl && now >= this.lanRetryAfter) {
      this.cachedLanUrl = this.configuredLanUrl;
      this.cachedLanSource = 'configured';
    }
    if (!this.cachedLanUrl && now < this.lanRetryAfter) return null;

    if (this.cachedLanUrl) {
      if (this.validatedLanAt > 0 && now - this.validatedLanAt <= this.validationTtlMs) {
        return this.cachedLanUrl;
      }
      try {
        await this.probe(this.cachedLanUrl);
        this.validatedLanAt = this.now();
        this.lanRetryAfter = 0;
        return this.cachedLanUrl;
      } catch (error) {
        if (this.cachedLanSource === 'configured' && error?.kind !== 'unreachable') throw error;
        this.cachedLanUrl = null;
        this.cachedLanSource = null;
        this.validatedLanAt = 0;
      }
    }

    if (!this.discovery) {
      this.lanRetryAfter = this.now() + this.lanRetryMs;
      return null;
    }
    const candidates = await this.discover();
    for (const candidate of candidates) {
      let url;
      try { url = validateLanOrigin(candidate); }
      catch { continue; }
      try {
        await this.probe(url);
        this.cachedLanUrl = url;
        this.cachedLanSource = 'discovered';
        this.validatedLanAt = this.now();
        this.lanRetryAfter = 0;
        return url;
      } catch {
        // Discovery is unauthenticated; reject spoofed/unreachable candidates and continue.
      }
    }
    this.lanRetryAfter = this.now() + this.lanRetryMs;
    return null;
  }

  noteSuccess(url, transport) {
    if (transport !== 'lan') return;
    let validated;
    try { validated = validateLanOrigin(url); }
    catch { return; }
    this.cachedLanUrl = validated;
    if (!this.cachedLanSource) this.cachedLanSource = 'discovered';
    this.validatedLanAt = this.now();
    this.lanRetryAfter = 0;
  }

  noteFailure(url, transport, error = null) {
    if (transport !== 'lan' || !['unreachable', 'outcome_unknown'].includes(error?.kind ?? 'outcome_unknown')) return;
    let validated;
    try { validated = validateLanOrigin(url); }
    catch { return; }
    if (this.cachedLanUrl && this.cachedLanUrl !== validated) return;
    this.cachedLanUrl = null;
    this.cachedLanSource = null;
    this.validatedLanAt = 0;
    this.lanRetryAfter = this.now() + this.lanRetryMs;
  }

}
