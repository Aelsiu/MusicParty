import { SUPPORTED_ORIGINS, G0_APPROVED } from './config.js';

const label = '[a-z0-9](?:[a-z0-9-]*[a-z0-9])?';
export const CDN_PATTERNS = Object.freeze([
  `^https://(?:${label}\\.)+bilivideo\\.com(?::443)?/[^#]+$`,
  `^https://(?:${label}\\.)+mcdn\\.bilivideo\\.cn(?::(?:443|8082))?/[^#]+$`,
]);

export function supportedOrigin(value) {
  try {
    const url = new URL(value);
    return !url.username && !url.password && SUPPORTED_ORIGINS.includes(url.origin);
  } catch { return false; }
}

// For G0 diagnostic matching only. This function never registers rules.
export function compileCandidate(tabId, origin) {
  if (!Number.isSafeInteger(tabId) || tabId < 0 || !SUPPORTED_ORIGINS.includes(origin)) {
    throw new Error('UNSUPPORTED_CONTEXT');
  }
  return CDN_PATTERNS.map((regexFilter, index) => ({
    id: index + 1,
    priority: 1,
    action: { type: 'modifyHeaders', requestHeaders: [
      { header: 'referer', operation: 'set', value: 'https://www.bilibili.com/' },
    ] },
    condition: {
      tabIds: [tabId],
      initiatorDomains: [new URL(origin).hostname],
      requestDomains: [index === 0 ? 'bilivideo.com' : 'mcdn.bilivideo.cn'],
      regexFilter,
      isUrlFilterCaseSensitive: false,
      resourceTypes: ['xmlhttprequest', 'media'],
      requestMethods: ['get'],
    },
  }));
}

export function compileEnabledRules(tabId, origin) {
  if (!G0_APPROVED) throw new Error('G0_ORIGIN_ISOLATION_UNVERIFIED');
  return compileCandidate(tabId, origin);
}
