export const PLAYBACK_PROTOCOL = 3;

export function createPlaybackRequestId(cryptoProvider = globalThis.crypto) {
    return cryptoProvider?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

// Routing only: selected page validation belongs to the trusted server metadata.
export function isDirectVideoInput(input) {
    const value = String(input || '').trim();
    if (/^BV[0-9A-Za-z]+(?::p\d+)?$/.test(value)) return true;
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && ['www.bilibili.com', 'bilibili.com'].includes(url.hostname)
            && url.pathname.startsWith('/video/');
    } catch { return false; }
}

// An epoch may only be established by a full state in the current connection.
export function createPlaybackBarrier() {
    let generation = 0, current = null;
    return {
        reset(nextGeneration) { generation = nextGeneration; current = null; },
        state(state, callbackGeneration) {
            if (callbackGeneration !== generation || state?.protocolVersion !== PLAYBACK_PROTOCOL
                || !state.playbackEpoch || !['MUSIC', 'VIDEO'].includes(state.mediaMode)
                || !Number.isSafeInteger(state.modeEpoch) || !Number.isSafeInteger(state.stateRevision)
                || !Number.isSafeInteger(state.queueRevision)) return null;
            if (current && (state.playbackEpoch !== current.playbackEpoch
                || state.modeEpoch < current.modeEpoch
                || state.stateRevision <= current.stateRevision
                || (state.modeEpoch === current.modeEpoch && state.mediaMode !== current.mediaMode))) return null;
            const changed = !current || state.modeEpoch !== current.modeEpoch;
            const keepQueue = !changed && state.queueRevision < current.queueRevision;
            current = { playbackEpoch: state.playbackEpoch, mediaMode: state.mediaMode,
                modeEpoch: state.modeEpoch, stateRevision: state.stateRevision,
                queueRevision: keepQueue ? current.queueRevision : state.queueRevision };
            return { changed, keepQueue, ...current };
        },
        queue(message, callbackGeneration) {
            if (callbackGeneration !== generation || !current || message?.protocolVersion !== PLAYBACK_PROTOCOL
                || !this.matches(message) || !Number.isSafeInteger(message.queueRevision)
                || message.queueRevision <= current.queueRevision || !Array.isArray(message.queue)) return false;
            current.queueRevision = message.queueRevision;
            return true;
        },
        matches(message) { return !!current && message?.playbackEpoch === current.playbackEpoch
            && message.modeEpoch === current.modeEpoch && message.mediaMode === current.mediaMode; },
        get ready() { return !!current; },
        get generation() { return generation; }
    };
}

export function createServerClock() {
    let samples = [], anchor = null;
    return {
        reset() { samples = []; anchor = null; },
        sample({ clientSentAt, performanceSentAt, clientReceivedAt, performanceReceivedAt,
            serverReceivedAt, serverSentAt }) {
            const elapsed = performanceReceivedAt - performanceSentAt;
            const serverElapsed = serverSentAt - serverReceivedAt;
            if (![clientSentAt, performanceSentAt, clientReceivedAt, performanceReceivedAt,
                serverReceivedAt, serverSentAt].every(Number.isFinite) || elapsed < 0 || serverElapsed < 0
                || Math.abs(clientReceivedAt - clientSentAt - elapsed) > 100 || serverElapsed > elapsed + 100) return false;
            const offset = ((serverReceivedAt - clientSentAt) + (serverSentAt - clientReceivedAt)) / 2;
            samples.push({ rtt: Math.max(0, elapsed - serverElapsed), server: clientReceivedAt + offset,
                performance: performanceReceivedAt });
            samples = samples.sort((a, b) => a.rtt - b.rtt).slice(0, 5);
            const best = samples[0];
            anchor = { server: best.server, performance: best.performance };
            return true;
        },
        now(monotonicNow) { return anchor ? anchor.server + monotonicNow - anchor.performance : null; },
        get ready() { return !!anchor; }
    };
}

export function videoBvid(id) { return String(id || '').match(/BV[0-9A-Za-z]+/)?.[0] || ''; }
