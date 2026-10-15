import { computed, ref, watch, onMounted, onBeforeUnmount, unref } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { roomSession } from '../services/roomSession';
import { createDanmakuTimeline } from '../utils/danmakuTimeline.js';
import { applyVideoAlignment } from '../utils/videoAlignment.js';
import { danmakuLayout } from '../utils/danmakuSettings.js';

const unavailable = '弹幕暂时不可用，视频继续播放';
const safeCodes = new Set(['CLIENT_UPGRADE_REQUIRED', 'CONTENT_UNAVAILABLE', 'STALE_CONTEXT', 'DANMAKU_BUSY',
    'DANMAKU_UNAVAILABLE', 'DANMAKU_LIMIT_EXCEEDED', 'PLATFORM_NOT_AVAILABLE']);
const failure = error => {
    const status = Number(error?.response?.status) || null;
    const code = safeCodes.has(error?.response?.data?.code) ? error.response.data.code : null;
    if (error?.danmakuContract) return { failureKind: 'CONTRACT', status, code, retryable: true, message: unavailable };
    if (code === 'CLIENT_UPGRADE_REQUIRED' || status === 404) return { failureKind: 'PROTOCOL', status, code, retryable: false, message: '弹幕接口不兼容，请刷新页面或更新服务' };
    if (status === 401 || status === 403) return { failureKind: 'ACCESS', status, code, retryable: false, message: '弹幕访问失效，请重新进入房间' };
    if (status === 409) return { failureKind: 'CONTEXT', status, code, retryable: true, message: '弹幕状态已变化，请同步后重试' };
    if (status === 429) return { failureKind: 'BUSY', status, code, retryable: true, message: unavailable };
    // A gateway response does not identify the upstream or decoder failure stage.
    return { failureKind: status ? 'UNKNOWN' : 'NETWORK', status, code, retryable: error?.response?.data?.retryable !== false, message: unavailable };
};

function validSegment(data, segment, descriptor) {
    const start = (segment - 1) * 360000, end = Math.min(segment * 360000, descriptor.durationMs);
    if (data.cid !== descriptor.cid || data.canonicalId !== descriptor.canonicalId || data.segmentIndex !== segment
        || data.startMs !== start || data.endMs !== end || !Array.isArray(data.items) || data.items.length > 6000
        || data.empty !== (data.items.length === 0)) return false;
    let previous = start;
    const ids = new Set();
    return data.items.every(entry => {
        if (!entry || typeof entry.id !== 'string' || !/^[1-9][0-9]{0,19}$/.test(entry.id) || BigInt(entry.id) > 18446744073709551615n || ids.has(entry.id)
            || !Number.isSafeInteger(entry.progressMs) || entry.progressMs < previous || entry.progressMs >= end
            || !Number.isInteger(entry.mode) || entry.mode < 1 || entry.mode > 6
            || ![18, 25, 30].includes(entry.fontSize) || !Number.isInteger(entry.color) || entry.color < 0 || entry.color > 0xffffff
            || typeof entry.text !== 'string' || Array.from(entry.text).length > 100) return false;
        ids.add(entry.id); previous = entry.progressMs; return true;
    });
}

export function useDanmaku(videoRef, player, options = {}) {
    const api = options.api || videoApi, session = options.session || roomSession;
    const now = options.now || (() => performance.now());
    const enabled = ref(false), visible = ref([]), mediaTime = ref(0), stateRevision = ref(0), clockNow = ref(now()), retryAt = ref(0);
    try { enabled.value = localStorage.getItem('mp_danmaku') === 'on'; } catch {}
    let alive = true, mounted = false, attachedMedia = null, generation = 0, timer, frame = null, noticeTimer;
    const schedule = options.schedule || setTimeout, cancelTimer = options.cancel || clearTimeout;
    const timeline = createDanmakuTimeline(), empty = [];
    const states = new Map(), pending = new Map();
    const key = () => `${session.roomId}:${session.generation}:${player.playbackEpoch}:${player.modeEpoch}:${player.nowPlaying?.playbackId}:${player.nowPlaying?.videoDescriptor?.cid}`;
    const active = () => alive && enabled.value && !document.hidden && player.mediaMode === 'VIDEO' && player.synchronized && !player.videoAutoAdvance && !!player.nowPlaying?.videoDescriptor && player.nowPlaying.videoDescriptor.provider!=='xifan';
    const drawingTime = () => videoRef.value?.presentationTime ?? videoRef.value?.currentTime ?? 0;
    const currentSegment = ref(1);
    const changed = () => { stateRevision.value++; };
    const clearDrawing = () => { visible.value = []; timeline.clear(); };
    const alignDrawing = consume => applyVideoAlignment(videoRef.value, alignment => {
        visible.value = timeline.align(alignment.from, alignment.target);
    }, consume);
    const cancel = () => {
        cancelTimer(noticeTimer); noticeTimer = undefined;
        generation++;
        for (const [segment, controller] of pending) {
            controller.abort();
            const state = states.get(segment);
            if (state?.status === 'LOADING') state.status = 'IDLE';
        }
        pending.clear(); cancelAnimationFrame(frame); frame = null; clearDrawing(); changed();
    };
    const incident = computed(() => {
        stateRevision.value;
        const state = states.get(currentSegment.value);
        return active() && state?.status === 'FAILED' && !state.dismissed && (state.visibleDeadline === undefined || now() < state.visibleDeadline) ? state.incident : null;
    });
    const retryable = computed(() => { stateRevision.value; const state = states.get(currentSegment.value); return enabled.value && state?.status === 'FAILED' && state.retryable; });
    function noticeExpiry() {
        cancelTimer(noticeTimer); noticeTimer = undefined;
        const state = states.get(currentSegment.value);
        if (!active() || state?.status !== 'FAILED' || state.dismissed) return;
        if (state.visibleDeadline === undefined) state.visibleDeadline = now() + 3000;
        if (state.visibleDeadline <= now()) { state.dismissed = true; changed(); return; }
        const id = state.incident.incidentId, instance = key(), token = generation;
        noticeTimer = schedule(() => {
            if (!alive || token !== generation || key() !== instance || states.get(currentSegment.value)?.incident?.incidentId !== id) return;
            state.dismissed = true; changed();
        }, state.visibleDeadline - now());
    }
    watch(incident, noticeExpiry, { flush: 'sync' });
    const error = computed(() => incident.value?.message || '');
    const retryRemainingMs = computed(() => Math.max(0, retryAt.value - clockNow.value));
    const retryEnabled = computed(() => {
        stateRevision.value;
        const state = states.get(currentSegment.value);
        return !!(active() && pending.size === 0 && state?.status === 'FAILED' && state.retryable && retryRemainingMs.value === 0);
    });
    const diagnostics = computed(() => {
        stateRevision.value;
        return [...states].map(([segmentIndex, state]) => ({ segmentIndex, status: state.status, requestRole: state.requestRole,
            attemptRevision: state.attemptRevision, failureKind: state.failureKind || null, httpStatus: state.statusCode || null,
            code: state.code || null, itemCount: state.items?.length || 0 }));
    });
    const load = async (segment, requestRole) => {
        let state = states.get(segment);
        if (!active() || (state && state.status !== 'IDLE') || pending.size >= 2) return;
        const context = player.playbackContext(), instance = key(), sourceRevision = player.nowPlaying.sourceRevision;
        const clientGeneration = player.clientGeneration, roomId = session.roomId;
        if (!context) return;
        state = { status: 'LOADING', requestRole, attemptRevision: (state?.attemptRevision || 0) + 1 };
        states.set(segment, state);
        const token = generation, controller = new AbortController(); pending.set(segment, controller); changed();
        const current = () => token === generation && !controller.signal.aborted && active() && key() === instance
            && player.isContextCurrent(context) && player.clientGeneration === clientGeneration && player.nowPlaying.sourceRevision === sourceRevision
            && states.get(segment) === state;
        try {
            const data = await api.danmakuSegment(segment, context, player.nowPlaying.playbackId, controller.signal);
            if (!current()) return;
            if (!matchesVideoRead(data, context, player, roomId) || data.playbackId !== player.nowPlaying.playbackId
                || !validSegment(data, segment, player.nowPlaying.videoDescriptor)) throw { danmakuContract: true };
            state.status = data.items.length ? 'READY' : 'EMPTY'; state.items = data.items;
        } catch (cause) {
            if (current() && cause?.name !== 'AbortError' && cause?.code !== 'ERR_CANCELED') {
                const details = failure(cause);
                Object.assign(state, details, { status: 'FAILED', statusCode: details.status, dismissed: false,
                    incident: { instanceKey: instance, segmentIndex: segment, attemptRevision: state.attemptRevision,
                        incidentId: `${instance}:${segment}:${state.attemptRevision}`, message: details.message } });
            }
        } finally {
            if (pending.get(segment) === controller) pending.delete(segment);
            if (states.get(segment) === state && state.status === 'LOADING') state.status = 'IDLE';
            changed();
        }
    };
    const poll = () => {
        clockNow.value = now();
        if (!active()) { if (pending.size || visible.value.length) cancel(); return; }
        const time = Math.max(0, Number(drawingTime()) || 0), segment = Math.floor(time / 360) + 1;
        currentSegment.value = segment;
        for (const number of states.keys()) if (number !== segment && number !== segment + 1) {
            pending.get(number)?.abort(); pending.delete(number); states.delete(number);
        }
        changed(); noticeExpiry();
        const duration = player.nowPlaying.videoDescriptor.durationMs;
        if ((segment - 1) * 360000 < duration) load(segment, 'CURRENT');
        if (segment * 360000 < duration) load(segment + 1, 'PREFETCH');
        if (frame === null) frame = requestAnimationFrame(draw);
    };
    const draw = () => {
        frame = null;
        clockNow.value = now();
        if (active() && videoRef.value) {
            alignDrawing(false);
            const time = drawingTime(); mediaTime.value = time;
            const layout = options.settings ? danmakuLayout(unref(options.viewport)?.height, unref(options.settings)) : null;
            visible.value = timeline.frame(states.get(Math.floor(time / 360) + 1)?.items || empty, time, videoRef.value.paused,
                layout ? { laneCount: layout.laneCount, speed: layout.settings.speed } : undefined);
        } else clearDrawing();
        if (active()) frame = requestAnimationFrame(draw);
    };
    const dismiss = () => {
        const state = states.get(currentSegment.value);
        if (state?.status !== 'FAILED' || !incident.value) return false;
        state.dismissed = true; cancelTimer(noticeTimer); noticeTimer = undefined; changed(); return true;
    };
    const retry = () => {
        clockNow.value = now();
        if (!retryEnabled.value) return false;
        retryAt.value = clockNow.value + 5000;
        for (const state of states.values()) if (state.status === 'FAILED' && state.retryable) state.status = 'IDLE';
        changed(); poll(); return true;
    };
    watch(enabled, value => { try { localStorage.setItem('mp_danmaku', value ? 'on' : 'off'); } catch {} cancel(); if (value) poll(); });
    // An instance and its source normally change in the same state commit. One watcher
    // avoids starting a pair of requests just to abort/restart it in another watcher.
    watch(() => [key(), player.clientGeneration, player.nowPlaying?.sourceRevision, player.synchronized, player.mediaMode, !!player.videoAutoAdvance], (value, previous) => {
        // Full state heartbeats replace nowPlaying and thus this getter's array,
        // even though the playback/source/session scalars have not changed.
        if (value.every((item, index) => Object.is(item, previous[index]))) return;
        cancel();
        if (value[0] !== previous[0]) { states.clear(); retryAt.value = 0; }
        changed(); if (active()) poll();
    });
    const visibility = () => { cancel(); if (active()) poll(); };
    // Explicit room seeks must clear even when the target is only a few frames
    // away. Ordinary follower corrections retain text already in flight.
    watch(() => player.seekRevision, clearDrawing, { flush: 'sync' });
    const seek = () => {
        if (videoRef.value && alignDrawing(true)) mediaTime.value = videoRef.value.currentTime;
        else clearDrawing();
        poll();
    };
    const bindMedia = video => {
        attachedMedia?.removeEventListener?.('seeking', seek);
        attachedMedia = video;
        attachedMedia?.addEventListener?.('seeking', seek);
    };
    watch(videoRef, value => {
        if (!mounted) return;
        bindMedia(value); clearDrawing(); if (active()) poll();
    }, { flush: 'sync' });
    onMounted(() => {
        mounted = true;
        document.addEventListener('visibilitychange', visibility);
        bindMedia(videoRef.value);
        timer = setInterval(poll, 1000); poll();
    });
    onBeforeUnmount(() => {
        alive = false; cancel(); states.clear(); clearInterval(timer); cancelAnimationFrame(frame);
        mounted = false; document.removeEventListener('visibilitychange', visibility); bindMedia(null);
    });
    return { enabled, visible, mediaTime, error, incident, dismiss, retry, retryEnabled, retryRemainingMs, retryable, diagnostics };
}
