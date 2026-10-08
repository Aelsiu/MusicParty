import test from 'node:test';
import assert from 'node:assert/strict';
import { createRenderer, defineComponent, nextTick, reactive, ref } from 'vue';
import { useVideo } from '../src/composables/useVideo.js';

const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, createElement: () => ({}),
    createText: () => ({}), setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} });
function mount(t, overrides = {}, engineOptions = {}, uiOverrides = {}) {
    const original = { setInterval, clearInterval, setTimeout, clearTimeout }, timers = new Map(); let id = 0;
    globalThis.setInterval = callback => { timers.set(++id, callback); return id; };
    globalThis.setTimeout = callback => { timers.set(++id, callback); return id; };
    globalThis.clearInterval = globalThis.clearTimeout = token => timers.delete(token);
    const listeners = new Map();
    globalThis.document = { hidden: false, addEventListener: (key, fn) => listeners.set(key, fn), removeEventListener: key => listeners.delete(key) };
    globalThis.window = { addEventListener: (key, fn) => listeners.set(key, fn), removeEventListener: key => listeners.delete(key) };
    let target = 20000;
    const refreshed = [];
    const player = reactive({ playbackEpoch: 'runtime', mediaMode: 'VIDEO', synchronized: true, isPaused: true,
        seekRevision: 0, isLoading: false, isBuffering: false, isErrorState: false, sourceRetryAt: null,
        nowPlaying: { playbackId: 'instance', sourceRevision: 1, music: { url: 'https://media.example/video.mp4', duration: 60000 } },
        getCurrentProgress: () => target, refreshVideoSource: reason => { refreshed.push(reason); return true; },
        resync() {}, sampleClock() {}, tryReconnect() {}, ...overrides });
    const video = { readyState: 1, duration: 60, currentTime: 0, paused: true, playbackRate: 1, volume: 0, muted: false, seeking: false,
        seekable: { length: 0 }, loads: 0, pauses: 0, plays: 0, error: null,
        load() { this.loads++; }, pause() { this.pauses++; this.paused = true; },
        play() { this.plays++; this.paused = false; return Promise.resolve(); },
        removeAttribute(name) { delete this[name]; }, getAttribute(name) { return this[name]; } };
    let engine;
    const ui = reactive({ audioVolume: .25, ...uiOverrides });
    const app = renderer.createApp(defineComponent({ setup() { engine = useVideo(ref(video), player, ui, engineOptions); return () => null; } }));
    app.mount({});
    t.after(() => { app.unmount(); Object.assign(globalThis, original); });
    return { video, player, ui, engine, refreshed, timers, listeners, app, setTarget: value => { target = value; } };
}
// Media seeks emit readiness/playback events; a passive currentTime property cannot detect seek loops.
function mediaEvents({ video, engine }) {
    let position = video.currentTime, seeks = 0;
    const events = [];
    Object.defineProperty(video, 'currentTime', { configurable: true,
        get: () => position,
        set: value => {
            seeks++; position = value; video.seeking = true; video.readyState = 2;
            events.push(() => {
                engine.onWaiting(); video.seeking = false; video.readyState = 3;
                engine.onReady(); // seeked and canplay
                if (!video.paused) engine.onPlaying();
            });
        }
    });
    video.play = () => {
        video.plays++; video.paused = false;
        events.push(() => engine.onPlaying());
        return Promise.resolve();
    };
    return {
        get seeks() { return seeks; },
        advance(value) { position = value; engine.onTimeUpdate(); },
        async settle() {
            for (let i = 0; i < 20; i++) {
                events.shift()?.();
                await Promise.resolve();
                if (!events.length) break;
            }
            assert.equal(events.length, 0, 'seek-generated canplay/playing events must settle without more seeks');
        }
    };
}
test('source revision reloads even the same URL and ready aligns retained paused position', async t => {
    const { video, player, engine } = mount(t);
    engine.onReady(); assert.equal(video.currentTime, 20); assert.equal(video.paused, true);
    const loads = video.loads;
    player.nowPlaying.sourceRevision = 2; await nextTick();
    assert.equal(video.loads, loads + 2);
    engine.onReady(); assert.equal(video.currentTime, 20); assert.equal(video.plays, 0);
});
test('autoplay unlock is local and never starts a paused room', async t => {
    const { video, player, engine } = mount(t, { isPaused: false });
    video.play = () => { video.plays++; return Promise.reject(Object.assign(new Error(), { name: 'NotAllowedError' })); };
    await engine.playLocal(); assert.equal(engine.needsInteraction.value, true);
    assert.equal(player.isPaused, false);
    player.isPaused = true; await nextTick(); const count = video.plays;
    await engine.playLocal(); assert.equal(video.plays, count);
});
test('late play completion after unmount stops old media and cannot change new state', async t => {
    const { video, player, engine, app, timers, listeners } = mount(t, { isPaused: false });
    let resolve; video.play = () => new Promise(done => { resolve = done; });
    const pending = engine.playLocal(); app.unmount(); resolve(); await pending;
    assert.equal(video.paused, true); assert.equal(video.src, undefined);
    assert.equal(timers.size, 0); assert.equal(listeners.size, 0); assert.equal(player.localProgress, 0);
});
test('network failure refreshes a source once while unsupported codec stays local', t => {
    const { video, engine, refreshed, player } = mount(t);
    video.error = { code: 2 }; engine.onError(); engine.onError();
    assert.deepEqual(refreshed, ['MEDIA_NETWORK_ERROR']); assert.equal(player.isPaused, true);
    video.error = { code: 3 }; engine.onError();
    assert.equal(refreshed.length, 1); assert.match(engine.errorMessage.value, /编码/);
    video.error = { code: 4 }; engine.onError();
    assert.equal(refreshed.length, 1); assert.match(engine.errorMessage.value, /编码或视频源不可用/);
});
test('foreground corrections use 500ms threshold and clamp to actual duration', async t => {
    const { video, player, engine, timers, setTarget } = mount(t);
    engine.onReady(); player.isPaused = false; await nextTick(); await Promise.resolve();
    video.currentTime = 19.8;
    for (const callback of timers.values()) callback();
    assert.equal(video.currentTime, 19.8);
    video.currentTime = 19;
    for (const callback of timers.values()) callback();
    assert.equal(video.currentTime, 20);
    setTarget(90000); engine.onReady(); assert.equal(video.currentTime, 59.999);
    assert.equal(video.playbackRate, 1);
});
test('seek-generated readiness events settle while first alignment, small room seeks and 500ms drift still work', async t => {
    const mounted = mount(t, { isPaused: false });
    const { video, player, engine, timers, setTarget } = mounted;
    const media = mediaEvents(mounted);
    engine.onReady(); await media.settle();
    assert.equal(media.seeks, 1); assert.equal(video.currentTime, 20);
    assert.equal(video.plays, 1); assert.equal(player.isBuffering, false);
    assert.equal(video.muted, false); assert.equal(video.volume, .25);
    for (let i = 0; i < 20; i++) { engine.onReady(); engine.onPlaying(); }
    await media.settle(); assert.equal(media.seeks, 1);

    setTarget(20040); player.seekRevision++; await nextTick(); await media.settle();
    assert.equal(media.seeks, 2); assert.equal(video.currentTime, 20.04, 'acknowledged small seek is still applied');
    setTarget(20400); timers.values().next().value(); await media.settle();
    assert.equal(media.seeks, 2, 'normal drift below 500ms must not interrupt playback');
    setTarget(20600); timers.values().next().value(); await media.settle();
    assert.equal(media.seeks, 3); assert.equal(video.currentTime, 20.6);
    assert.equal(player.isBuffering, false);
});
test('download stalled events do not show buffering when playable data or advancing media remains', async t => {
    const mounted = mount(t, { isPaused: false });
    const { video, engine, player, refreshed, timers } = mounted;
    const media = mediaEvents(mounted);
    engine.onReady(); await media.settle();
    video.readyState = 4; engine.onStalled(); engine.onWaiting();
    assert.equal(player.isBuffering, false);
    video.readyState = 2; engine.onWaiting();
    assert.equal(player.isBuffering, true);
    media.advance(20.2);
    assert.equal(player.isBuffering, false, 'real time advancement clears buffering even without a new playing event');
    engine.onStalled(); assert.equal(player.isBuffering, false);
    assert.equal(timers.size, 1, 'false buffering does not leave a source-refresh timeout');
    assert.deepEqual(refreshed, []);
    player.isPaused = true; await nextTick(); engine.onWaiting();
    assert.equal(player.isBuffering, false);
});
test('heartbeat replacements with identical source scalars do not reload or seek current media', async t => {
    const mounted = mount(t, { isPaused: false });
    const { video, player, engine } = mounted;
    const media = mediaEvents(mounted);
    engine.onReady(); await media.settle();
    const loads = video.loads, seeks = media.seeks, plays = video.plays;
    for (let revision = 1; revision <= 3; revision++) {
        player.nowPlaying = { ...player.nowPlaying, currentPosition: 20000, serverTime: revision * 5000,
            music: { ...player.nowPlaying.music } };
        await nextTick(); await media.settle();
    }
    assert.equal(video.loads, loads);
    assert.equal(media.seeks, seeks);
    assert.equal(video.plays, plays);
    assert.equal(video.paused, false);
    player.nowPlaying = { ...player.nowPlaying, sourceRevision: player.nowPlaying.sourceRevision + 1 };
    await nextTick();
    assert.equal(video.loads, loads + 2, 'a changed source revision still reloads even with the same URL');
});
test('room wait pauses presentation, preserves local eligibility and checks frozen data before READY', async t => {
    const reports = [];
    const { video, player, engine } = mount(t, { isPaused: false, playIntent: 'PLAYING', playbackRate: 1.5,
        reportVideoWatch: report => { reports.push(report); return true; } });
    video.readyState = 4; video.buffered = { length: 1, start: () => 19, end: () => 30 };
    engine.onReady(); await Promise.resolve();
    assert.equal(video.playbackRate, 1.5); assert.equal(reports.at(-1).status, 'READY');
    const plays = video.plays;
    player.videoWait = { waitId: 'barrier', waitRevision: 1, frozenPosition: 25000 };
    player.isPaused = true; await nextTick();
    assert.equal(video.paused, true); assert.equal(video.currentTime, 25);
    engine.onReady(); engine.onPlaying(); await engine.playLocal();
    assert.equal(video.plays, plays, 'no readiness, playing or local play callback can resume a wait');
    engine.reportStatus();
    assert.equal(reports.at(-1).status, 'READY'); assert.equal(reports.at(-1).waitId, 'barrier');
    assert.equal(reports.at(-1).playbackAllowed, true, 'a programmatic wait pause preserves qualification');
    video.buffered.end = () => 26;
    engine.reportStatus(); assert.equal(reports.at(-1).status, 'BUFFERING', 'paused wait still lacks the rate-adjusted buffer');
    player.playIntent = 'PAUSED'; player.videoWait = null; await nextTick(); engine.onReady();
    assert.equal(video.plays, plays, 'manual pause remains paused after readiness');
});
test('watch reports exclude autoplay denial, background and unsynchronized viewers and carry monotonic source context', async t => {
    const reports = [];
    const { video, player, engine } = mount(t, { isPaused: false, reportVideoWatch: value => reports.push(value) });
    video.play = () => Promise.reject(Object.assign(Error(), { name: 'NotAllowedError' }));
    await engine.playLocal(); engine.reportStatus();
    assert.equal(reports.at(-1).status, 'BLOCKED'); assert.equal(reports.at(-1).playbackAllowed, false);
    document.hidden = true; engine.reportStatus(); assert.equal(reports.at(-1).status, 'INACTIVE');
    document.hidden = false; const old = reports.at(-1);
    player.nowPlaying.sourceRevision++; await nextTick(); engine.reportStatus();
    assert.ok(reports.at(-1).sourceGeneration > old.sourceGeneration);
    assert.ok(reports.at(-1).sequence > old.sequence);
    const count = reports.length; player.synchronized = false; engine.reportStatus();
    assert.equal(reports.length, count);
});
test('a qualified MP4 source replacement reports real missing data while paused and still allows prebuffering', async t => {
    const reports = [];
    const { video, player, engine } = mount(t, { isPaused: false, playIntent: 'PLAYING', reportVideoWatch: value => reports.push(value) });
    video.readyState = 4; video.buffered = { length: 1, start: () => 19, end: () => 30 };
    engine.onReady(); await Promise.resolve(); assert.equal(reports.at(-1).status, 'READY');
    player.nowPlaying.sourceRevision++; await nextTick();
    video.readyState = 1; video.buffered = { length: 0 };
    engine.reportStatus();
    assert.equal(video.paused, true); assert.equal(reports.at(-1).status, 'BUFFERING');
    assert.equal(reports.at(-1).playbackAllowed, true);
    video.readyState = 4; video.buffered = { length: 1, start: () => 19, end: () => 30 };
    engine.onReady(); await Promise.resolve(); engine.reportStatus();
    assert.equal(video.paused, false); assert.equal(reports.at(-1).status, 'READY');
});
test('recoverable network failure keeps an established viewer buffering while decoding failure exits eligibility', async t => {
    const reports = [];
    const { video, engine } = mount(t, { isPaused: false, playIntent: 'PLAYING', reportVideoWatch: value => reports.push(value) });
    video.readyState = 4; video.buffered = { length: 1, start: () => 19, end: () => 30 };
    engine.onReady(); await Promise.resolve(); assert.equal(reports.at(-1).status, 'READY');
    video.error = { code: 2 }; engine.onError();
    assert.equal(reports.at(-1).status, 'BUFFERING'); assert.equal(reports.at(-1).playbackAllowed, true);
    video.error = { code: 3 }; engine.onError(); assert.equal(reports.at(-1).status, 'ERROR');
});
test('a stale play promise cannot pause a newer source that has already started playing', async t => {
    const { video, player, engine } = mount(t, { isPaused: false });
    let complete; video.play = () => new Promise(resolve => { complete = resolve; });
    const pending = engine.playLocal();
    player.nowPlaying.sourceRevision++; await nextTick();
    video.play = () => { video.paused = false; return Promise.resolve(); };
    await engine.playLocal(); const pauses = video.pauses;
    complete(); await pending;
    assert.equal(video.pauses, pauses); assert.equal(video.paused, false);
});
test('DASH initializes separate compatible tracks and quality swaps isolate old errors without room controls', async t => {
    const oldMse = globalThis.MediaSource;
    globalThis.MediaSource = { isTypeSupported: () => true };
    t.after(() => { globalThis.MediaSource = oldMse; });
    const engines = [], reports = [];
    function MediaPlayer() { return { create() {
        const handlers = new Map();
        const engine = { handlers, resets: 0, initialize(video, manifest, autoPlay, position) { this.initialized = { manifest, autoPlay, position }; },
            updateSettings(value) { this.settings = value; }, on(key, fn) { handlers.set(key, fn); }, reset() { this.resets++; },
            addRequestInterceptor(value) { this.requestInterceptor = value; }, addResponseInterceptor(value) { this.responseInterceptor = value; },
            getDashMetrics: () => ({ getCurrentBufferLevel: () => 10 }) };
        engines.push(engine); return engine;
    } }; }
    MediaPlayer.events = { ERROR: 'error', QUALITY_CHANGE_RENDERED: 'quality', STREAM_INITIALIZED: 'initialized' };
    const base = { id: 80, quality: 80, qualityId: 'q80', qualityLabel: '1080p', mimeType: 'video/mp4', codecs: 'avc1.640028',
        bandwidth: 2000000, width: 1920, height: 1080, initialization: '0-999', indexRange: '1000-2000', url: 'https://cdn.example/video' };
    const nowPlaying = { playbackId: 'instance', sourceRevision: 1, music: { duration: 60000, url: null },
        videoSource: { format: 'dash', mp4: { url: 'https://cdn.example/muxed.mp4' }, dash: { durationMs: 60000, videoTracks: [base, { ...base, id: 64, quality: 64, qualityId: 'q64', qualityLabel: '720p' }],
            audioTracks: [{ ...base, id: 30280, qualityId: 'audio-30280', mimeType: 'audio/mp4', codecs: 'mp4a.40.2' }] } } };
    const { video, player, ui, engine, refreshed, timers } = mount(t, { nowPlaying, reportVideoWatch: value => reports.push(value) },
        { loadDash: async () => ({ MediaPlayer }), probeFetch: async () => ({ status: 206, type: 'cors', body: { cancel: async () => {} } }) },
        { videoQualityPreference: { version: 1, mode: 'auto' } });
    for (let i = 0; i < 20; i++) await Promise.resolve(); await nextTick();
    assert.equal(engines.length, 1); assert.equal(player.videoDelivery, 'DASH');
    assert.equal(engines[0].initialized.autoPlay, false); assert.equal(engines[0].initialized.position, 20);
    assert.equal(engines[0].settings.streaming.scheduling.scheduleWhilePaused, true);
    const xml = await (await fetch(engines[0].initialized.manifest)).text();
    assert.match(xml, /contentType="audio"/); assert.match(xml, /contentType="video"/);
    engines[0].handlers.get('initialized')(); video.readyState = 4; engine.onReady();
    const progress = player.getCurrentProgress();
    ui.videoQualityPreference = { version: 1, mode: 'manual', qualityId: 'q64' };
    await nextTick(); for (let i = 0; i < 20; i++) await Promise.resolve();
    assert.equal(engines.length, 2); assert.equal(engines[0].resets, 1);
    engines[0].handlers.get('error')();
    assert.equal(player.isErrorState, false); assert.equal(engine.errorMessage.value, '');
    assert.equal(player.getCurrentProgress(), progress); assert.deepEqual(refreshed, []);
    const manualXml = await (await fetch(engines[1].initialized.manifest)).text();
    assert.match(manualXml, /video-64-0/); assert.doesNotMatch(manualXml, /video-80/);
    engines[1].handlers.get('error')({ error: { code: 27 } });
    engines[1].handlers.get('error')({ error: { code: 27 } });
    assert.equal(engines[1].resets, 0, 'library error dispatch must complete before the engine is reset');
    assert.equal(player.videoDelivery, 'DASH');
    [...timers.values()].at(-1)();
    assert.equal(engines[1].resets, 1); assert.equal(player.videoDelivery, 'MP4');
});

test('P1-compatible MP4 source removal stops already playing media and cannot restart buffered bytes', async t => {
    const {video,player,engine}=mount(t,{isPaused:false});
    await engine.playLocal(); assert.equal(video.paused,false);
    player.nowPlaying.music.url=null; player.nowPlaying.videoSource=null; player.nowPlaying.sourceRevision=2; player.isPaused=true; player.sourceStatus='ERROR';
    await nextTick(); assert.equal(video.paused,true); assert.equal(video.src,undefined);
    const plays=video.plays; await engine.playLocal(); assert.equal(video.plays,plays);
});
test('P1-compatible DASH policy stop resets buffered DASH and clears media element', async t => {
    const oldMse = globalThis.MediaSource;
    globalThis.MediaSource = { isTypeSupported: () => true };
    t.after(() => { globalThis.MediaSource = oldMse; });
    const engines = [], reports = [];
    function MediaPlayer() { return { create() {
        const handlers = new Map();
        const engine = { handlers, resets: 0, initialize(video, manifest, autoPlay, position) { this.initialized = { manifest, autoPlay, position }; },
            updateSettings(value) { this.settings = value; }, on(key, fn) { handlers.set(key, fn); }, reset() { this.resets++; },
            addRequestInterceptor(value) { this.requestInterceptor = value; }, addResponseInterceptor(value) { this.responseInterceptor = value; },
            getDashMetrics: () => ({ getCurrentBufferLevel: () => 10 }) };
        engines.push(engine); return engine;
    } }; }
    MediaPlayer.events = { ERROR: 'error', QUALITY_CHANGE_RENDERED: 'quality', STREAM_INITIALIZED: 'initialized' };
    const base = { id: 80, quality: 80, qualityId: 'q80', qualityLabel: '1080p', mimeType: 'video/mp4', codecs: 'avc1.640028',
        bandwidth: 2000000, width: 1920, height: 1080, initialization: '0-999', indexRange: '1000-2000', url: 'https://cdn.example/video' };
    const nowPlaying = { playbackId: 'instance', sourceRevision: 1, music: { duration: 60000, url: null },
        videoSource: { format: 'dash', mp4: { url: 'https://cdn.example/muxed.mp4' }, dash: { durationMs: 60000, videoTracks: [base, { ...base, id: 64, quality: 64, qualityId: 'q64', qualityLabel: '720p' }],
            audioTracks: [{ ...base, id: 30280, qualityId: 'audio-30280', mimeType: 'audio/mp4', codecs: 'mp4a.40.2' }] } } };
    const { video, player, ui, engine, refreshed, timers } = mount(t, { nowPlaying, reportVideoWatch: value => reports.push(value) },
        { loadDash: async () => ({ MediaPlayer }), probeFetch: async () => ({ status: 206, type: 'cors', body: { cancel: async () => {} } }) },
        { videoQualityPreference: { version: 1, mode: 'auto' } });
    for (let i = 0; i < 20; i++) await Promise.resolve(); await nextTick();
    assert.equal(engines.length, 1); assert.equal(player.videoDelivery, 'DASH');
    assert.equal(engines[0].initialized.autoPlay, false); assert.equal(engines[0].initialized.position, 20);
    assert.equal(engines[0].settings.streaming.scheduling.scheduleWhilePaused, true);
    const xml = await (await fetch(engines[0].initialized.manifest)).text();
    assert.match(xml, /contentType="audio"/); assert.match(xml, /contentType="video"/);
    engines[0].handlers.get('initialized')(); video.readyState = 4; engine.onReady();
    player.nowPlaying.videoSource=null; player.nowPlaying.music.url=null; player.nowPlaying.sourceRevision++; player.isPaused=true; player.sourceStatus='ERROR';
    await nextTick(); assert.equal(engines[0].resets,1); assert.equal(video.paused,true); assert.equal(video.src,undefined);
    const plays=video.plays; await engine.playLocal(); assert.equal(video.plays,plays);
});
