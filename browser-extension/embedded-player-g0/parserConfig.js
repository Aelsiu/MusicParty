export const PARSER_SCRIPT_ID = 'mirthparty-parser-bridge';
export const CDN_ORIGINS = Object.freeze(['https://*.bilivideo.com/*', 'https://*.mcdn.bilivideo.cn/*']);

export function normalizeParserOrigin(value) {
  if (typeof value !== 'string') throw Error('INVALID_PARSER_ORIGIN');
  const text = value.trim();
  if (!/^https?:\/\/[^/?#]/i.test(text) || /[\u0000-\u001f\u007f\\*]/.test(value)
    || text.includes(' ')) throw Error('INVALID_PARSER_ORIGIN');
  let url;
  try { url = new URL(text); } catch { throw Error('INVALID_PARSER_ORIGIN'); }
  if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password
    || url.origin === 'null') throw Error('INVALID_PARSER_ORIGIN');
  return url.origin;
}

// Chromium permission matching is broader than a URL path. The worker separately
// requires exact scheme/host/port for every document, command and renderer.
export function parserOriginPattern(value) {
  return `${normalizeParserOrigin(value)}/*`;
}

export function validParserIdentity(value) {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
