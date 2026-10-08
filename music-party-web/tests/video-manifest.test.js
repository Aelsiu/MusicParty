import test from 'node:test';
import assert from 'node:assert/strict';
import { chooseDashTracks, createDashManifest, playableAt } from '../src/utils/videoManifest.js';
import { readVideoQuality, saveVideoQuality, normalizeVideoQuality } from '../src/utils/videoQuality.js';
import { createVideoWaitEventFilter } from '../src/utils/videoWaitEvents.js';

const track = (quality, codec = 'avc1.64001F') => ({ id: quality, quality, qualityId: `q${quality}`, qualityLabel: `${quality}p`,
    mimeType: 'video/mp4', codecs: codec, bandwidth: quality * 10000, width: 1280, height: 720, frameRate: '30',
    initialization: '0-999', indexRange: '1000-2000', url: 'https://cdn.example/video?token=a&b=c',
    backupUrls: ['https://secondary.example/video'] });
const audio = { ...track(30280, 'mp4a.40.2'), mimeType: 'audio/mp4', qualityId: 'audio-30280' };
const mse = { isTypeSupported: type => !type.includes('hev1') };
const source = { dash: { durationMs: 60000, minBufferTime: 1.5, videoTracks: [track(80), track(64), track(32), track(80, 'hev1.1.6')], audioTracks: [audio] } };
test('quality preference is versioned, stable, URL-free and fails safely on broken storage', () => {
    for (const bad of [null, {}, { version: 2, mode: 'manual', qualityId: 'q80' }, { version: 1, mode: 'manual', qualityId: 'https://evil.example' }]) {
        assert.deepEqual(normalizeVideoQuality(bad), { version: 1, mode: 'auto' });
    }
    const stored = new Map(), storage = { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) };
    saveVideoQuality({ version: 1, mode: 'manual', qualityId: 'q80', url: 'secret', generation: 99 }, storage);
    assert.deepEqual(readVideoQuality(storage), { version: 1, mode: 'manual', qualityId: 'q80' });
    assert.equal([...stored.values()].some(value => value.includes('secret')), false);
    assert.deepEqual(readVideoQuality({ getItem() { throw Error(); } }), { version: 1, mode: 'auto' });
    assert.doesNotThrow(() => saveVideoQuality({}, { setItem() { throw Error(); } }));
});
test('compatible DASH tracks preserve automatic ABR and manual preference falls back locally', () => {
    const automatic = chooseDashTracks(source, { mode: 'auto' }, mse);
    assert.deepEqual(automatic.videoTracks.map(t => t.quality), [32, 64, 80]);
    assert.equal(automatic.audioTracks.length, 1);
    const preference = { version: 1, mode: 'manual', qualityId: 'q116' };
    assert.deepEqual(chooseDashTracks(source, preference, mse).videoTracks.map(t => t.quality), [80]);
    assert.equal(preference.qualityId, 'q116');
    assert.deepEqual(chooseDashTracks(source, preference, null).videoTracks, []);
});
test('generated static MPD contains two codec sets, SIDX ranges, escaped URLs and trusted backup CDNs', () => {
    const tracks = chooseDashTracks(source, { mode: 'auto' }, mse);
    const xml = createDashManifest(source.dash, tracks.videoTracks, tracks.audioTracks);
    assert.match(xml, /contentType="audio"/); assert.match(xml, /contentType="video"/);
    assert.match(xml, /codecs="mp4a\.40\.2"/); assert.match(xml, /indexRange="1000-2000"/);
    assert.match(xml, /Initialization range="0-999"/); assert.match(xml, /token=a&amp;b=c/);
    assert.match(xml, /secondary\.example/); assert.match(xml, /mediaPresentationDuration="PT60S"/);
    assert.throws(() => createDashManifest(source.dash, tracks.videoTracks, []));
});
test('MPD keeps the original primary endpoint ahead of a standard-port backup without changing query data', () => {
    const primary = { ...track(80), url: 'https://main.example:8082/video?signature=exact%2Fvalue&deadline=123', backupUrls: ['https://backup.example/video?signature=exact%2Fvalue&deadline=123'] };
    const xml = createDashManifest(source.dash, [primary], [audio]);
    assert.ok(xml.indexOf('main.example:8082') < xml.indexOf('backup.example'));
    assert.match(xml, /signature=exact%2Fvalue&amp;deadline=123/);
});
test('wait readiness requires positioning, decoded combined buffer and both DASH tracks at rate-adjusted water', () => {
    const video = { readyState: 4, currentTime: 20, duration: 60, seeking: false, error: null,
        buffered: { length: 1, start: () => 19, end: () => 23 } };
    const levels = { audio: 3, video: 3 }, dash = { getDashMetrics: () => ({ getCurrentBufferLevel: type => levels[type] }) };
    assert.equal(playableAt(video, 20, 2, dash), true);
    levels.audio = .8; assert.equal(playableAt(video, 20, 2, dash), false);
    levels.audio = 3; video.seeking = true; assert.equal(playableAt(video, 20, 1, dash), false);
    video.seeking = false; assert.equal(playableAt(video, 19, 1, dash), false);
    video.buffered.end = () => 20.5; assert.equal(playableAt(video, 20, 1, dash), false);
    video.currentTime = 59.9; video.buffered = { length: 1, start: () => 58, end: () => 60 };
    levels.audio = .1; levels.video = .1; assert.equal(playableAt(video, 59.9, 2, dash), true, 'tail does not demand nonexistent extra seconds');
    video.readyState = 2; assert.equal(playableAt(video, 59.9, 1, dash), false, 'canplay alone is insufficient');
});
test('timeout events deduplicate public/private delivery and accept current instance after the barrier clears', () => {
    const filter = createVideoWaitEventFilter(2), player = { mediaMode: 'VIDEO', nowPlaying: { playbackId: 'p1' }, videoWait: null, acceptsEvent: event => event.playbackEpoch === 'epoch' };
    const event = { action: 'VIDEO_WAIT_TIMEOUT', eventId: 'e1', waitId: 'w1', playbackId: 'p1', playbackEpoch: 'epoch' };
    assert.equal(filter(event, player), true); assert.equal(filter(event, player), false);
    assert.equal(filter({ ...event, eventId: 'e2', playbackId: 'old-p' }, player), false);
    assert.equal(filter({ ...event, eventId: 'e2', playbackEpoch: 'old-epoch' }, player), false);
    assert.equal(filter({ ...event, eventId: 'e2' }, player), true);
    assert.equal(filter({ ...event, eventId: 'e3' }, player), true);
    assert.equal(filter(event, player), true, 'the bounded set evicts oldest events');
});
