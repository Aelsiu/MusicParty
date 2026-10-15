import {normalizeParserOrigin} from './parserConfig.js';
export const PROTOCOL = 1;
export function exactOrigin(value, origin) {
  try { return normalizeParserOrigin(origin) === origin && normalizeParserOrigin(value) === origin; } catch { return false; }
}
export function trustedOwner(sender, top, origin) {
  return Number.isSafeInteger(sender?.tab?.id) && sender.tab.id >= 0 && sender.frameId === 0
    && typeof sender.documentId === 'string' && sender.documentId.length > 0
    && exactOrigin(sender.url, origin) && exactOrigin(sender.tab.url, origin)
    && top?.documentId === sender.documentId && exactOrigin(top.url, origin);
}
export function trustedRenderer(sender, top, owner, extensionOrigin) {
  // Extension iframe MessageSender lacks content-script frame/document fields.
  // Its window is attested by a private MessageChannel transferred directly
  // to the owner's exact iframe.contentWindow, not absent sender fields.
  return !!owner && owner.frameAttested === true && sender?.tab?.id === owner.tabId
    && top?.documentId === owner.documentId && exactOrigin(top.url, owner.origin)
    && sender.url === `${extensionOrigin}/renderer.html#${owner.token}`;
}
export function cdnUrl(value) {
  if (typeof value !== 'string' || value.length > 8192) return false;
  try {
    const u = new URL(value);
    if (u.protocol !== 'https:' || u.username || u.password || u.hash || u.pathname === '/') return false;
    const label = '[a-z0-9](?:[a-z0-9-]*[a-z0-9])?';
    return (new RegExp(`^(?:${label}\\.)+bilivideo\\.com$`).test(u.hostname) && !u.port)
      || (new RegExp(`^(?:${label}\\.)+mcdn\\.bilivideo\\.cn$`).test(u.hostname) && ['', '8082'].includes(u.port));
  } catch { return false; }
}
export function rendererRules(tabId, extensionId, ruleIds = [1, 2]) {
  if (!Number.isSafeInteger(tabId) || tabId < 0 || tabId > 2147483647 || !/^[a-p]{32}$/.test(extensionId)
    || !Array.isArray(ruleIds) || ruleIds.length !== 2 || new Set(ruleIds).size !== 2
    || !ruleIds.every(id=>Number.isSafeInteger(id)&&id>0&&id<=2147483647)) throw Error('INVALID_RULE_CONTEXT');
  const label = '[a-z0-9](?:[a-z0-9-]*[a-z0-9])?';
  return [
    `^https://(?:${label}\\.)+bilivideo\\.com(?::443)?/[^#]+$`,
    `^https://(?:${label}\\.)+mcdn\\.bilivideo\\.cn(?::(?:443|8082))?/[^#]+$`,
  ].map((regexFilter, i) => ({ id: ruleIds[i], priority: 1,
    action: {type:'modifyHeaders',requestHeaders:[{header:'referer',operation:'set',value:'https://www.bilibili.com/'}]},
    condition: {tabIds:[tabId],initiatorDomains:[extensionId],regexFilter,isUrlFilterCaseSensitive:false,
      requestMethods:['get'],resourceTypes:['media','xmlhttprequest']} }));
}
const object = v => v && typeof v === 'object' && !Array.isArray(v);
const finite = (n, min, max) => Number.isFinite(n) && n >= min && n <= max;
const presentationId = v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v);
export function validateCommand(m) {
  if (!object(m) || JSON.stringify(m).length > 131072 || !Number.isSafeInteger(m.seq) || m.seq < 0
    || typeof m.context !== 'string' || !m.context || m.context.length > 256) return false;
  if (m.type === 'STOP') return Object.keys(m).every(k=>['type','seq','context'].includes(k));
  if (m.frameOwnerId !== undefined && !presentationId(m.frameOwnerId)) return false;
  if (m.type === 'RESET_SOURCE') return presentationId(m.frameOwnerId) && Object.keys(m).every(k=>['type','seq','context','frameOwnerId'].includes(k));
  if (m.type === 'SYNC') return Object.keys(m).every(k=>['type','seq','context','position','paused','rate','volume','frameOwnerId','retainPausedFrame'].includes(k))
    && (m.retainPausedFrame === undefined || typeof m.retainPausedFrame === 'boolean' && presentationId(m.frameOwnerId))
    && finite(m.position,0,86400) && typeof m.paused === 'boolean' && finite(m.rate,.5,2) && finite(m.volume,0,1);
  if (m.type !== 'LOAD' || !Object.keys(m).every(k=>['type','seq','context','source','frameOwnerId'].includes(k)) || !object(m.source)) return false;
  const s = m.source;
  if (s.kind === 'mp4') return Object.keys(s).every(k=>['kind','url'].includes(k)) && cdnUrl(s.url);
  if (s.kind !== 'dash' || !Object.keys(s).every(k=>['kind','durationMs','videoTracks','audioTracks'].includes(k))
    || !finite(s.durationMs,1,86400000)) return false;
  const track = (t,type) => object(t) && Number.isSafeInteger(t.id) && t.id>=0 && t.id<=100000
    && Object.keys(t).every(k=>['id','url','backupUrls','bandwidth','codecs','mimeType','initialization','indexRange','width','height','frameRate','quality','qualityId','qualityLabel'].includes(k))
    && cdnUrl(t.url) && Array.isArray(t.backupUrls||[]) && (t.backupUrls||[]).length<=8
    && (t.backupUrls||[]).every(cdnUrl) && finite(t.bandwidth,1,1000000000)
    && /^(avc1|avc3|hev1|hvc1|av01|vp09|mp4a|opus)[.a-zA-Z0-9-]*$/.test(t.codecs||'')
    && t.mimeType === `${type}/mp4` && /^\d{1,10}-\d{1,10}$/.test(t.initialization||'')
    && /^\d{1,10}-\d{1,10}$/.test(t.indexRange||'')
    && [t.initialization,t.indexRange].every(r=>Number(r.split('-')[0])<=Number(r.split('-')[1]))
    && (type !== 'video' || (finite(t.width,1,16384) && finite(t.height,1,16384)));
  return ['video','audio'].every(type=>Array.isArray(s[`${type}Tracks`]) && s[`${type}Tracks`].length>0
    && s[`${type}Tracks`].length<=16 && s[`${type}Tracks`].every(t=>track(t,type)));
}
