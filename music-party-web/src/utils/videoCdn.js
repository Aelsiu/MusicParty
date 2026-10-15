export function trustedTrackUrls(track) {
    return [...new Set([track?.url, ...(track?.backupUrls || [])])].filter(value => {
        try { return new URL(value).protocol === 'https:'; } catch { return false; }
    }).slice(0, 4);
}

// A small direct Range probe checks browser CORS access without reading or retaining media bytes.
export async function selectReachableTrack(tracks, { signal, fetcher = globalThis.fetch,
    budgetMs = 6000, attemptTimeoutMs = 1200, now = Date.now, onDiagnostics } = {}) {
    const startedAt = now(), deadline = startedAt + budgetMs;
    const diagnostics = { candidates: Math.min(tracks.length, 6), attempts: 0, responses: {}, timeouts: 0, transportFailures: 0 };
    const finish = result => {
        // Counts only: signed URLs, response bodies and exception messages never enter diagnostics.
        try { onDiagnostics?.({ ...diagnostics, responses: { ...diagnostics.responses },
            elapsedMs: Math.max(0, now() - startedAt), aborted: !!signal?.aborted, selected: !!result }); } catch {}
        return result;
    };
    if (!fetcher || signal?.aborted) return finish(null);
    for (const track of tracks.slice(0, 6)) {
        const urls = trustedTrackUrls(track);
        for (const url of urls) {
            const remaining = deadline - now();
            if (remaining <= 0 || signal?.aborted) return finish(null);
            const controller = new AbortController();
            const abort = () => controller.abort();
            signal?.addEventListener('abort', abort, { once: true });
            const timer = setTimeout(abort, Math.min(attemptTimeoutMs, remaining));
            try {
                diagnostics.attempts++;
                const response = await fetcher(url, { method: 'GET', mode: 'cors', credentials: 'omit',
                    cache: 'no-store', referrerPolicy: 'no-referrer', headers: { Range: 'bytes=0-63' }, signal: controller.signal });
                const status = Number.isInteger(response.status) && response.status >= 100 && response.status <= 599 ? response.status : 'unknown';
                diagnostics.responses[status] = (diagnostics.responses[status] || 0) + 1;
                // Only a readable 206 proves the Range transport DASH will need. No body is consumed.
                const reachable = response.status === 206 && response.type !== 'opaque';
                await response.body?.cancel().catch(() => {});
                if (reachable && !signal?.aborted && !controller.signal.aborted) return finish({ ...track, url, backupUrls: urls.filter(candidate => candidate !== url) });
            } catch {
                if (!signal?.aborted) {
                    if (controller.signal.aborted) diagnostics.timeouts++;
                    else diagnostics.transportFailures++;
                }
            }
            finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
        }
    }
    return finish(null);
}
// Try every AAC primary before spending the shared budget on backups. A slow or blocked
// high-bitrate representation must not prevent a lower complete audio track from loading.
export async function selectReachableAudio(tracks, { signal, fetcher = globalThis.fetch,
    budgetMs = 6000, attemptTimeoutMs = 2400, concurrency = 3, now = Date.now, onDiagnostics } = {}) {
    const startedAt = now(), deadline = startedAt + budgetMs;
    const candidates = tracks.slice(0, 6).map(track => ({ track, urls: trustedTrackUrls(track) }));
    const pending = [];
    for (let ordinal = 0; ordinal < 4; ordinal++) {
        for (const candidate of candidates) if (candidate.urls[ordinal]) pending.push({ ...candidate, url: candidate.urls[ordinal] });
    }
    const diagnostics = { candidates: candidates.length, attempts: 0, responses: {}, timeouts: 0, transportFailures: 0 };
    const controller = new AbortController(), abort = () => controller.abort();
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    let next = 0, selected = null;
    const record = value => {
        diagnostics.attempts += value.attempts;
        diagnostics.timeouts += value.timeouts;
        diagnostics.transportFailures += value.transportFailures;
        for (const [status, count] of Object.entries(value.responses)) diagnostics.responses[status] = (diagnostics.responses[status] || 0) + count;
    };
    const worker = async () => {
        while (next < pending.length && !controller.signal.aborted && fetcher) {
            const remaining = deadline - now();
            if (remaining <= 0) return;
            const candidate = pending[next++];
            const reachable = await selectReachableTrack([{ ...candidate.track, url: candidate.url, backupUrls: [] }], {
                signal: controller.signal, fetcher, budgetMs: remaining, attemptTimeoutMs, now, onDiagnostics: record
            });
            if (reachable && !signal?.aborted && !selected) {
                selected = { ...candidate.track, url: candidate.url, backupUrls: candidate.urls.filter(url => url !== candidate.url) };
                // The current source has a readable complete audio track; stop every losing Range request.
                abort();
            }
        }
    };
    try {
        await Promise.all(Array.from({ length: Math.min(pending.length, Math.max(1, Math.min(3, concurrency))) }, worker));
    } finally { signal?.removeEventListener('abort', abort); }
    if (signal?.aborted) selected = null;
    try { onDiagnostics?.({ ...diagnostics, responses: { ...diagnostics.responses }, elapsedMs: Math.max(0, now() - startedAt),
        aborted: !!signal?.aborted, selected: !!selected }); } catch {}
    return selected;
}

// dash.js initializes SIDX for every representation, so one inaccessible tier must be left out.
export async function reachableVideoTracks(tracks, { signal, fetcher = globalThis.fetch, budgetMs = 6000,
    attemptTimeoutMs = 1200, concurrency = 3, now = Date.now } = {}) {
    const candidates = tracks.slice(0, 16), found = new Map(), deadline = now() + budgetMs;
    let next = 0;
    const worker = async () => {
        while (next < candidates.length && !signal?.aborted) {
            const index = next++, remaining = deadline - now();
            if (remaining <= 0) return;
            const track = await selectReachableTrack([candidates[index]], { signal, fetcher,
                budgetMs: remaining, attemptTimeoutMs, now });
            if (track && !signal?.aborted) found.set(index, track);
        }
    };
    await Promise.all(Array.from({ length: Math.min(candidates.length, Math.max(1, Math.min(3, concurrency))) }, worker));
    if (signal?.aborted) return [];
    return [...found].sort((a, b) => a[0] - b[0]).map(([, track]) => track);
}

// SegmentBase SIDX requests do not reliably take dash.js's fragment BaseURL blacklist path.
// Interceptors rotate only within one track; codec, initialization and SIDX ranges stay unchanged.
export function createDashCdnRouter(tracks, isCurrent = () => true) {
    const byUrl = new Map();
    for (const track of tracks) {
        const urls = trustedTrackUrls(track), state = { urls, selected: 0, failed: new Set() };
        for (const url of urls) byUrl.set(url, state);
    }
    const request = async value => {
        const state = byUrl.get(value.url);
        if (isCurrent() && state) value.url = state.urls[state.selected];
        return value;
    };
    const response = async value => {
        if (!isCurrent()) return value;
        const requested = value.request?.url || value.url;
        const state = byUrl.get(requested);
        if (state && !(value.status >= 200 && value.status < 300)) {
            state.failed.add(requested);
            // Late failures of an earlier concurrent request cannot skip a newer candidate.
            if (state.urls[state.selected] === requested) {
                const next = state.urls.findIndex(url => !state.failed.has(url));
                if (next >= 0) state.selected = next;
            }
        }
        return value;
    };
    return { request, response };
}
