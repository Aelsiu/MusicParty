export function trustedTrackUrls(track) {
    return [...new Set([track?.url, ...(track?.backupUrls || [])])].filter(value => {
        try { return new URL(value).protocol === 'https:'; } catch { return false; }
    }).slice(0, 4);
}

// A small direct Range probe checks browser CORS access without reading or retaining media bytes.
export async function selectReachableTrack(tracks, { signal, fetcher = globalThis.fetch,
    budgetMs = 6000, attemptTimeoutMs = 1200, now = Date.now } = {}) {
    if (!fetcher || signal?.aborted) return null;
    const deadline = now() + budgetMs;
    for (const track of tracks.slice(0, 6)) {
        const urls = trustedTrackUrls(track);
        for (const url of urls) {
            const remaining = deadline - now();
            if (remaining <= 0 || signal?.aborted) return null;
            const controller = new AbortController();
            const abort = () => controller.abort();
            signal?.addEventListener('abort', abort, { once: true });
            const timer = setTimeout(abort, Math.min(attemptTimeoutMs, remaining));
            try {
                const response = await fetcher(url, { method: 'GET', mode: 'cors', credentials: 'omit',
                    cache: 'no-store', referrerPolicy: 'no-referrer', headers: { Range: 'bytes=0-63' }, signal: controller.signal });
                // Only a readable 206 proves the Range transport DASH will need. No body is consumed.
                const reachable = response.status === 206 && response.type !== 'opaque';
                await response.body?.cancel().catch(() => {});
                if (reachable && !signal?.aborted) return { ...track, url, backupUrls: urls.filter(candidate => candidate !== url) };
            } catch { /* The next original trusted URL or audio representation may be reachable. */ }
            finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
        }
    }
    return null;
}
export const selectReachableAudio = selectReachableTrack;

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
