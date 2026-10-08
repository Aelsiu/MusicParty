import test from 'node:test';
import assert from 'node:assert/strict';
import { selectReachableAudio, trustedTrackUrls, createDashCdnRouter, reachableVideoTracks } from '../src/utils/videoCdn.js';

const top = { id: 30280, url: 'https://blocked.example/top?signature=original%2Fvalue&token=full', backupUrls: ['https://blocked2.example/top?signature=original%2Fvalue&token=full'] };
const reachable = { id: 30232, codecs: 'mp4a.40.2', initialization: '0-808', indexRange: '809-1010',
    url: 'https://main.example:8082/audio?signature=unchanged%2Fvalue&token=full', backupUrls: ['https://backup.example/audio?signature=unchanged%2Fvalue&token=full'] };
test('audio preflight cancels every body and selects a readable 206 original source without rewriting signatures or retaining media', async () => {
    const requests = [], cancellations = [];
    const selected = await selectReachableAudio([top, reachable], { fetcher: async (url, init) => {
        requests.push({ url, init });
        return { status: url === reachable.url ? 206 : 403, type: 'cors', body: {
            cancel: async () => cancellations.push(url), getReader() { assert.fail('media bytes must not be read'); } } };
    } });
    assert.equal(selected.id, 30232); assert.equal(selected.url, reachable.url);
    assert.equal(selected.indexRange, reachable.indexRange); assert.equal(selected.initialization, reachable.initialization);
    assert.deepEqual(requests.map(item => item.url), [top.url, ...top.backupUrls, reachable.url]);
    assert.deepEqual(cancellations, requests.map(item => item.url));
    assert.ok(requests.every(({ init }) => init.headers.Range === 'bytes=0-63' && init.credentials === 'omit' && init.mode === 'cors' && init.cache === 'no-store'));
    assert.ok(requests.every(({ init }) => !init.headers.Referer && !init.headers.Origin));
});
test('audio preflight rejects unreadable/non-Range response and has an overall deadline', async () => {
    let clock = 0, calls = 0;
    const result = await selectReachableAudio([top, reachable], { budgetMs: 100, now: () => clock, fetcher: async () => {
        calls++; clock += 100; return { status: 200, type: 'cors', body: { cancel: async () => {} } };
    } });
    assert.equal(result, null); assert.equal(calls, 1);
});
test('audio preflight aborts an old source immediately and cannot select it after source generation changed', async () => {
    const controller = new AbortController(); let began;
    const started = new Promise(resolve => { began = resolve; });
    const pending = selectReachableAudio([reachable], { signal: controller.signal, fetcher: (url, init) => new Promise((resolve, reject) => {
        init.signal.addEventListener('abort', () => reject(Object.assign(Error(), { name: 'AbortError' })), { once: true }); began();
    }) });
    await started; controller.abort(); assert.equal(await pending, null);
});
test('same-track SIDX and initialization retries rotate exact trusted URLs without changing ranges or crossing audio/video', async () => {
    const video = { url: 'https://main.example:8082/video?signature=video%2Ffull', backupUrls: ['https://backup.example/video?signature=video%2Ffull'] };
    const router = createDashCdnRouter([video, reachable]);
    const first = { url: video.url, headers: { Range: 'bytes=934-1129' } };
    assert.equal((await router.request(first)).url, video.url);
    await router.response({ status: 403, request: first });
    const retried = await router.request({ url: video.url, headers: { Range: 'bytes=934-1129' } });
    assert.equal(retried.url, video.backupUrls[0]); assert.equal(retried.headers.Range, 'bytes=934-1129');
    assert.equal((await router.request({ url: reachable.url })).url, reachable.url, 'video failure does not reroute audio');
    assert.equal((await router.request({ url: 'https://member.example/injected' })).url, 'https://member.example/injected', 'unknown requests are not accepted as fallback candidates');
    await router.response({ status: 206, request: retried });
    assert.equal((await router.request({ url: video.url, headers: { Range: 'bytes=0-933' } })).url, video.backupUrls[0], 'successful backup also serves the same track initialization');
});
test('late failure and stale-generation interceptor cannot skip candidates or rewrite the active player', async () => {
    let current = true;
    const track = { ...reachable, backupUrls: [...reachable.backupUrls, 'https://third.example/audio?signature=third-original'] };
    const router = createDashCdnRouter([track], () => current);
    await router.response({ status: 403, request: { url: track.url } });
    await router.response({ status: 403, request: { url: track.url } });
    assert.equal((await router.request({ url: track.url })).url, track.backupUrls[0], 'late concurrent failure does not skip the first backup');
    await router.response({ status: 403, request: { url: track.backupUrls[0] } });
    assert.equal((await router.request({ url: track.url })).url, track.backupUrls[1]);
    current = false;
    assert.equal((await router.request({ url: track.url })).url, track.url);
});
test('trusted candidates preserve primary first and exclude invalid transports with a bounded count', () => {
    const track = { url: reachable.url, backupUrls: ['http://untrusted.example', reachable.url, 'not-a-url', ...Array.from({ length: 10 }, (_, i) => `https://trusted-${i}.example/audio`)] };
    const urls = trustedTrackUrls(track);
    assert.equal(urls[0], reachable.url); assert.equal(urls.length, 4);
    assert.equal(urls.some(url => url.startsWith('http:')), false);
});
test('video preflight excludes an inaccessible tier while preserving reachable representations and their own ranges', async () => {
    const failed = { ...top, id: 16, qualityId: 'q16', initialization: '0-799', indexRange: '800-999' };
    const good = { ...reachable, id: 64, qualityId: 'q64', url: 'https://main.example:8082/video64?signature=untouched', initialization: '0-934', indexRange: '935-1130' };
    const videos = await reachableVideoTracks([failed, good], { fetcher: async url => ({ status: url === good.url ? 206 : 403,
        type: 'cors', body: { cancel: async () => {} } }) });
    assert.equal(videos.length, 1); assert.equal(videos[0].id, 64); assert.equal(videos[0].url, good.url);
    assert.equal(videos[0].initialization, '0-934'); assert.equal(videos[0].indexRange, '935-1130');
});
test('video preflight limits concurrency, overall budget and candidate count', async () => {
    let active = 0, peak = 0, calls = 0;
    const tracks = Array.from({ length: 20 }, (_, i) => ({ id: i, url: `https://primary-${i}.example/video`, backupUrls: [] }));
    const videos = await reachableVideoTracks(tracks, { concurrency: 20, fetcher: async () => {
        active++; peak = Math.max(peak, active); calls++; await Promise.resolve(); active--;
        return { status: 206, type: 'cors', body: { cancel: async () => {} } };
    } });
    assert.ok(peak <= 3); assert.equal(calls, 16); assert.equal(videos.length, 16);
    let clock = 0;
    const limited = await reachableVideoTracks(tracks, { concurrency: 1, budgetMs: 100, now: () => clock, fetcher: async () => {
        clock += 100; return { status: 403, type: 'cors', body: { cancel: async () => {} } };
    } });
    assert.deepEqual(limited, []);
});
test('cancelling a source aborts all parallel video preflights and discards every late result', async () => {
    const controller = new AbortController(); let started = 0, complete;
    const begun = new Promise(resolve => { complete = resolve; });
    const pending = reachableVideoTracks(Array.from({ length: 5 }, (_, i) => ({ ...reachable, url: `https://trusted-${i}.example/video` })), {
        signal: controller.signal, fetcher: (url, init) => new Promise((resolve, reject) => {
            started++; if (started === 3) complete();
            init.signal.addEventListener('abort', () => reject(Object.assign(Error(), { name: 'AbortError' })), { once: true });
        }) });
    await begun; controller.abort(); assert.deepEqual(await pending, []); assert.equal(started, 3);
});
