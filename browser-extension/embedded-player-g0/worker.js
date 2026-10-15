import {trustedOwner, trustedRenderer, rendererRules, validateCommand, PROTOCOL} from './policy.js';
import {normalizeParserOrigin, parserOriginPattern, validParserIdentity, CDN_ORIGINS, PARSER_SCRIPT_ID} from './parserConfig.js';

const mediaEvents = new Set(['canplay', 'loadedmetadata', 'seeked', 'play', 'playing', 'pause', 'waiting', 'ended', 'timeupdate', 'enterpictureinpicture', 'leavepictureinpicture']);
const ranges = value => Array.isArray(value) ? value.slice(0, 32).filter(r => Array.isArray(r) && r.length === 2
  && r.every(n => Number.isFinite(n) && n >= 0 && n <= 86400) && r[1] >= r[0]) : [];

export function createParserWorker(api, uuid = () => crypto.randomUUID()) {
  const owners = new Map();
  let serial = Promise.resolve(), nextRuleId = 1;
  const enqueue = fn => { const result = serial.then(fn); serial = result.catch(() => {}); return result; };
  const extensionOrigin = api.runtime.getURL('').replace(/\/$/, '');
  const permitted = origin => api.permissions.contains({origins: [parserOriginPattern(origin), ...CDN_ORIGINS]});
  const identity = owner => ({origin: owner.origin, revision: owner.revision, connectionId: owner.connectionId});
  const send = (port, owner, message) => port.postMessage({...message, ...identity(owner)});
  const rememberedOrigin = async () => {
    const value = (await api.storage.local.get('parserOrigin')).parserOrigin;
    try { return normalizeParserOrigin(value); } catch { return ''; }
  };
  async function activeConfig() {
    const saved = await api.storage.session.get(['enabled', 'parserOrigin', 'parserRevision']);
    if (saved.enabled !== true || !validParserIdentity(saved.parserRevision)) return null;
    try {
      const origin = normalizeParserOrigin(saved.parserOrigin);
      return origin === saved.parserOrigin ? {origin, revision: saved.parserRevision} : null;
    } catch { return null; }
  }
  const scriptFor = origin => ({id: PARSER_SCRIPT_ID, matches: [parserOriginPattern(origin)], js: ['bridge.js'],
    runAt: 'document_start', allFrames: false, world: 'ISOLATED', persistAcrossSessions: false});
  async function registered(origin) {
    const scripts = await api.scripting.getRegisteredContentScripts({ids: [PARSER_SCRIPT_ID]});
    const script = scripts[0];
    return scripts.length === 1 && script.id === PARSER_SCRIPT_ID && script.matches?.length === 1
      && script.matches[0] === parserOriginPattern(origin) && script.js?.length === 1 && script.js[0] === 'bridge.js'
      && script.runAt === 'document_start' && script.allFrames !== true && script.matchOriginAsFallback !== true
      && script.world === 'ISOLATED' && script.persistAcrossSessions === false;
  }
  async function unregister() {
    const scripts = await api.scripting.getRegisteredContentScripts({ids: [PARSER_SCRIPT_ID]});
    if (scripts.length) await api.scripting.unregisterContentScripts({ids: [PARSER_SCRIPT_ID]});
  }
  async function clearAll() {
    const rules = await api.declarativeNetRequest.getSessionRules();
    await api.declarativeNetRequest.updateSessionRules({removeRuleIds: rules.map(r => r.id)});
  }
  async function revoke(tabId, reason = 'REVOKED') {
    const owner = owners.get(tabId); owners.delete(tabId);
    if (owner) for (const port of new Set([owner.renderer, owner.candidate, owner.bridge])) {
      try { if (port) send(port, owner, reason === 'RECOVERY_RESET' ? {type:'RELEASED',reason} : {type: 'REVOKED'}); } catch {}
      try { port?.disconnect(); } catch {}
    }
    if (owner?.ruleIds?.length) await api.declarativeNetRequest.updateSessionRules({removeRuleIds: owner.ruleIds});
  }
  async function deactivate(origin = '') {
    // Disable first: already injected scripts cannot retain an enabled capability
    // while rule/script cleanup is in flight or fails.
    const revision = uuid();
    let failure;
    try { await api.storage.session.set({enabled: false, parserOrigin: origin, parserRevision: revision}); } catch (error) { failure = error; }
    for (const tabId of [...owners.keys()]) try { await revoke(tabId); } catch (error) { failure ||= error; }
    for (const operation of [unregister, clearAll]) try { await operation(); } catch (error) { failure ||= error; }
    if (failure) throw failure;
    return {enabled: false, origin, revision};
  }
  async function state() {
    let config = await activeConfig();
    if (config && (!(await permitted(config.origin)) || !(await registered(config.origin)))) {
      await deactivate(await rememberedOrigin()); config = null;
    }
    if (config) return {enabled: true, ...config};
    const saved = await api.storage.session.get(['enabled', 'parserRevision']);
    if (saved.enabled === true) return deactivate(await rememberedOrigin());
    // Readback also verifies that a prior failed cleanup has not left a rule or
    // registered injector behind; an error must remain visible until repaired.
    for (const tabId of [...owners.keys()]) await revoke(tabId);
    await unregister(); await clearAll();
    return {enabled: false, origin: await rememberedOrigin(), revision: saved.parserRevision || ''};
  }
  const boot = enqueue(async () => {
    // Worker recreation never reuses a document's capability or DNR rules.
    await clearAll();
    const saved = (await api.storage.session.get('nextRuleId')).nextRuleId;
    if (Number.isSafeInteger(saved) && saved > 0 && saved <= 2147483648) nextRuleId = saved;
    const config = await activeConfig();
    if (!config || !(await permitted(config.origin))) { await deactivate(await rememberedOrigin()); return; }
    if (!(await registered(config.origin))) { await unregister(); await api.scripting.registerContentScripts([scriptFor(config.origin)]); }
  });
  async function ownerCurrent(owner, sender, top) {
    const config = await activeConfig();
    return owners.get(owner.tabId) === owner && !!config && config.origin === owner.origin && config.revision === owner.revision
      && trustedOwner(sender, top, owner.origin) && await permitted(owner.origin) && await registered(owner.origin);
  }
  async function authorizeRenderer(owner) {
    if (owner?.renderer || !owner?.candidate || !owner.frameAttested || owners.get(owner.tabId) !== owner) return;
    const port = owner.candidate, tabId = owner.tabId;
    const top = await api.webNavigation.getFrame({tabId, frameId: 0});
    if (!(await ownerCurrent(owner, owner.bridge.sender, top)) || !trustedRenderer(port.sender, top, owner, extensionOrigin)) {
      await revoke(tabId); return;
    }
    if (nextRuleId > 2147483646) throw Error('RULE_IDS_EXHAUSTED');
    const rules = rendererRules(tabId, api.runtime.id, [nextRuleId++, nextRuleId++]);
    owner.ruleIds = rules.map(r => r.id);
    await api.storage.session.set({nextRuleId});
    const previous = await api.declarativeNetRequest.getSessionRules();
    await api.declarativeNetRequest.updateSessionRules({removeRuleIds: previous.filter(r => r.condition.tabIds?.includes(tabId)).map(r => r.id), addRules: rules});
    const current = await api.webNavigation.getFrame({tabId, frameId: 0});
    if (!(await ownerCurrent(owner, owner.bridge.sender, current))) { await revoke(tabId); return; }
    owner.renderer = port;
    send(port, owner, {type: 'AUTHORIZED', protocol: PROTOCOL, ruleIds: rules.map(r => r.id)});
    send(owner.bridge, owner, {type: 'READY', protocol: PROTOCOL, features: {pausedFrame: 1}});
    port.onMessage.addListener(m => enqueue(async () => {
      if (owners.get(tabId) !== owner || owner.renderer !== port || m?.type !== 'STATUS' || m.connectionId !== owner.connectionId || m.revision !== owner.revision) return;
      const current = await api.webNavigation.getFrame({tabId, frameId: 0});
      if (!(await ownerCurrent(owner, owner.bridge.sender, current))) { await revoke(tabId); return; }
      send(owner.bridge, owner, {type: 'STATUS', context: String(m.context || '').slice(0, 256), event: mediaEvents.has(m.event) ? m.event : 'timeupdate',
        buffered: ranges(m.buffered), seekable: ranges(m.seekable), position: Number(m.position) || 0, duration: Number(m.duration) || 0,
        paused: !!m.paused, readyState: Number(m.readyState) || 0, seeking: !!m.seeking, ended: !!m.ended,
        error: ['LOAD_FAILED', 'AUTOPLAY_BLOCKED'].includes(m.error) ? m.error : null});
    }).catch(() => enqueue(() => owners.get(tabId) === owner ? revoke(tabId) : undefined)));
  }
  api.runtime.onMessage.addListener((message, sender, reply) => {
    if (!['PARSER_STATE', 'PARSER_SET'].includes(message?.type)) return;
    // Runtime messages from content scripts/webpages must never change the site.
    // The same trusted extension page may be opened as an action popup or tab.
    // Content scripts carry their website URL, never this extension-page URL.
    if (sender.id !== api.runtime.id || sender.url !== api.runtime.getURL('popup.html')) return;
    enqueue(async () => {
      await boot.catch(() => {});
      if (message.type === 'PARSER_STATE') return state();
      if (typeof message.enabled !== 'boolean') throw Error('INVALID_PARSER_STATE');
      const origin = message.enabled ? normalizeParserOrigin(message.origin) : await rememberedOrigin();
      if (!message.enabled) return deactivate(origin);
      if (!(await permitted(origin))) throw Error('PARSER_PERMISSION_REQUIRED');
      await deactivate(origin);
      await api.storage.local.set({parserOrigin: origin});
      try {
        await api.scripting.registerContentScripts([scriptFor(origin)]);
        await api.storage.session.set({enabled: true, parserOrigin: origin, parserRevision: uuid()});
        return await state();
      } catch (error) { await deactivate(origin); throw error; }
    }).then(reply).catch(error => enqueue(async () => {
      const failure = error?.message || 'PARSER_OPERATION_FAILED';
      try { return {...await state(), error: failure}; }
      catch {
        const config = await activeConfig();
        return {enabled: !!config, origin: config?.origin || await rememberedOrigin(), revision: config?.revision || '', error: failure};
      }
    }).then(reply).catch(() => reply({enabled: false, origin: '', revision: '', error: 'PARSER_CLEANUP_FAILED'})));
    return true;
  });
  api.runtime.onConnect.addListener(port => {
    const sender = port.sender;
    let closed = false, rendererReady = false, helloSeen = false;
    port.onDisconnect.addListener(() => { closed = true; enqueue(async () => {
      const owner = owners.get(sender?.tab?.id);
      if (owner && (owner.bridge === port || owner.renderer === port || owner.candidate === port)) await revoke(owner.tabId);
    }); });
    if (port.name === 'mirth-owner') {
      port.onMessage.addListener(m => enqueue(async () => {
        await boot;
        if (!helloSeen) {
          helloSeen = true;
          const config = await activeConfig(), top = await api.webNavigation.getFrame({tabId: sender?.tab?.id, frameId: 0});
          if (closed || m?.type !== 'HELLO' || !validParserIdentity(m.connectionId) || !config
            || !(await permitted(config.origin)) || !(await registered(config.origin)) || !trustedOwner(sender, top, config.origin)) { port.disconnect(); return; }
          await revoke(sender.tab.id);
          if (closed) return;
          const owner = {tabId: sender.tab.id, documentId: sender.documentId, ...config, connectionId: m.connectionId,
            token: uuid(), bridge: port, renderer: null, seq: -1};
          owners.set(owner.tabId, owner);
          send(port, owner, {type: 'MOUNT', protocol: PROTOCOL, url: api.runtime.getURL(`renderer.html#${owner.token}`)}); return;
        }
        const owner = owners.get(sender?.tab?.id);
        if (closed || !owner || owner.bridge !== port || m?.connectionId !== owner.connectionId || m.revision !== owner.revision) return;
        const current = await api.webNavigation.getFrame({tabId: owner.tabId, frameId: 0});
        if (!(await ownerCurrent(owner, sender, current))) { await revoke(owner.tabId); return; }
        if (m.type === 'BIND_RENDERER') {
          if (m.token === owner.token && typeof owner.candidateChallenge === 'string' && m.challenge === owner.candidateChallenge) {
            owner.frameAttested = true; await authorizeRenderer(owner);
          } return;
        }
        if (m.type === 'RELEASE_FOR_RECOVERY') {
          if (validParserIdentity(m.frameOwnerId) && (!owner.frameOwnerId || m.frameOwnerId === owner.frameOwnerId)) await revoke(owner.tabId, 'RECOVERY_RESET');
          return;
        }
        const {connectionId, revision, ...command} = m;
        if (!validateCommand(command) || command.seq <= owner.seq) return;
        owner.seq = command.seq;
        if (command.type === 'LOAD') { owner.frameOwnerId = command.frameOwnerId; owner.context = command.context; }
        if (['SYNC','RESET_SOURCE'].includes(command.type) && (command.frameOwnerId !== owner.frameOwnerId || command.frameOwnerId && command.context !== owner.context)) return;
        if (owner.renderer) send(owner.renderer, owner, command);
      }).catch(() => enqueue(async () => {
        const owner = owners.get(sender?.tab?.id);
        if (owner?.bridge === port) await revoke(owner.tabId);
        else try { port.disconnect(); } catch {}
      })));
    } else if (port.name === 'mirth-renderer') {
      port.onMessage.addListener(m => {
        if (m?.type !== 'RENDERER_READY') return;
        rendererReady = true;
        enqueue(async () => {
          const owner = owners.get(sender?.tab?.id);
          if (owner?.candidate === port && !owner.renderer) send(port, owner, {type: 'WINDOW_CHALLENGE', challenge: owner.candidateChallenge});
        });
      });
      enqueue(async () => {
        await boot;
        const tabId = sender?.tab?.id, owner = owners.get(tabId);
        const top = await api.webNavigation.getFrame({tabId, frameId: 0});
        if (closed || !owner || owner.candidate || sender.url !== api.runtime.getURL(`renderer.html#${owner.token}`)
          || !(await ownerCurrent(owner, owner.bridge.sender, top))) { port.disconnect(); return; }
        owner.candidate = port; owner.candidateChallenge = uuid();
        if (rendererReady) send(port, owner, {type: 'WINDOW_CHALLENGE', challenge: owner.candidateChallenge});
        await authorizeRenderer(owner);
      }).catch(() => enqueue(async () => {
        const owner = owners.get(sender?.tab?.id);
        if (owner?.candidate === port) await revoke(owner.tabId);
        else try { port.disconnect(); } catch {}
      }));
    } else port.disconnect();
  });
  api.webNavigation.onBeforeNavigate.addListener(d => { if (d.frameId === 0) enqueue(() => revoke(d.tabId)); });
  api.webNavigation.onCommitted.addListener(d => { if (d.frameId === 0) enqueue(() => revoke(d.tabId)); });
  api.tabs.onRemoved.addListener(id => enqueue(() => revoke(id)));
  api.permissions.onRemoved.addListener(() => enqueue(async () => {
    const config = await activeConfig();
    if (config && !(await permitted(config.origin))) await deactivate(await rememberedOrigin());
  }));
  api.storage.onChanged.addListener((changes, area) => {
    if (area === 'session' && ['enabled', 'parserOrigin', 'parserRevision'].some(key => Object.hasOwn(changes, key))) enqueue(async () => {
      const config = await activeConfig();
      for (const owner of [...owners.values()]) if (!config || config.origin !== owner.origin || config.revision !== owner.revision) await revoke(owner.tabId);
      if (!config) { await unregister(); await clearAll(); }
    });
  });
  api.runtime.onInstalled.addListener(() => enqueue(async () => { await deactivate(await rememberedOrigin()); }));
  api.runtime.onStartup.addListener(() => enqueue(async () => { await deactivate(await rememberedOrigin()); }));
  // A rejected boot fails closed and is surfaced by popup requests.
  boot.catch(() => {});
  return {idle: async () => { await boot; let current; do { current = serial; await current; } while (current !== serial); }};
}

if (typeof chrome !== 'undefined') createParserWorker(chrome);
