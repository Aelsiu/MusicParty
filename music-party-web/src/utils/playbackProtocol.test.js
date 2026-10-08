import test from 'node:test';
import assert from 'node:assert/strict';
import { createPlaybackBarrier, createServerClock, videoBvid, isDirectVideoInput, createPlaybackRequestId } from './playbackProtocol.js';

const state = overrides => ({ protocolVersion: 3, playbackEpoch: 'runtime-a', mediaMode: 'MUSIC', modeEpoch: 0,
    stateRevision: 1, queueRevision: 1, queue: [], ...overrides });
test('only current connection full state establishes a barrier; legacy and stranger epochs do not', () => {
    const barrier = createPlaybackBarrier(); barrier.reset(3);
    assert.equal(barrier.queue(state({ queueRevision: 3 }), 3), false);
    assert.equal(barrier.state(state({ protocolVersion: 1 }), 3), null);
    assert.equal(barrier.state(state({ protocolVersion: 2 }), 3), null);
    assert.equal(barrier.state(state(), 2), null);
    assert.ok(barrier.state(state(), 3));
    assert.equal(barrier.state(state({ playbackEpoch: 'runtime-b', stateRevision: 8 }), 3), null);
    barrier.reset(4);
    assert.ok(barrier.state(state({ playbackEpoch: 'runtime-b' }), 4));
    assert.equal(barrier.state(state({ stateRevision: 99 }), 3), null);
});
test('out of order queue cannot switch modes and an old full state keeps newer queue', () => {
    const barrier = createPlaybackBarrier(); barrier.reset(1); barrier.state(state(), 1);
    assert.equal(barrier.queue(state({ mediaMode: 'VIDEO', modeEpoch: 1, queueRevision: 8 }), 1), false);
    assert.equal(barrier.queue(state({ queueRevision: 5 }), 1), true);
    assert.equal(barrier.state(state({ stateRevision: 2, queueRevision: 2 }), 1).keepQueue, true);
    assert.equal(barrier.state(state({ stateRevision: 1, queueRevision: 7 }), 1), null);
    const changed = barrier.state(state({ stateRevision: 3, modeEpoch: 1, mediaMode: 'VIDEO', queueRevision: 6 }), 1);
    assert.equal(changed.changed, true); assert.equal(changed.keepQueue, false);
    assert.equal(barrier.queue(state({ queueRevision: 9 }), 1), false);
});
test('server clock selects low RTT and progresses with monotonic time', () => {
    const clock = createServerClock();
    assert.equal(clock.now(10), null);
    clock.sample({ clientSentAt: 1000, clientReceivedAt: 1200, performanceSentAt: 0, performanceReceivedAt: 200,
        serverReceivedAt: 1610, serverSentAt: 1620 });
    clock.sample({ clientSentAt: 2000, clientReceivedAt: 2020, performanceSentAt: 1000, performanceReceivedAt: 1020,
        serverReceivedAt: 2510, serverSentAt: 2510 });
    assert.equal(clock.now(1520), 3020);
    assert.equal(clock.now(2020), 3520);
});
test('wall clock jumps and invalid server samples are discarded', () => {
    const clock = createServerClock();
    assert.equal(clock.sample({ clientSentAt: 1000, clientReceivedAt: 9010, performanceSentAt: 0, performanceReceivedAt: 10,
        serverReceivedAt: 1500, serverSentAt: 1501 }), false);
    assert.equal(clock.ready, false);
});
test('canonical first P IDs produce clean Bilibili source links without lowering BVID case', () => {
    assert.equal(videoBvid('BV1aB2cD3E4F:p1'), 'BV1aB2cD3E4F');
    assert.equal(videoBvid('https://www.bilibili.com/video/BV1aB2cD3E4F/?p=1'), 'BV1aB2cD3E4F');
});
test('direct video routing supports both page hosts and retains explicit page selection', () => {
    for (const input of ['BV1aB2cD3E4F', 'BV1aB2cD3E4F:p2',
        'https://www.bilibili.com/video/BV1aB2cD3E4F/?p=2',
        'https://bilibili.com/video/BV1aB2cD3E4F/']) assert.equal(isDirectVideoInput(input), true);
    for (const input of ['视频搜索关键词', 'https://b23.tv/short', 'https://bilibili.com.evil.example/video/BV1aB2cD3E4F/']) {
        assert.equal(isDirectVideoInput(input), false);
    }
});
test('operation IDs work without secure-context crypto.randomUUID', () => {
    assert.equal(createPlaybackRequestId({ randomUUID: () => 'native-operation-id' }), 'native-operation-id');
    assert.match(createPlaybackRequestId({}), /^\d+-[a-z0-9]+$/);
    assert.match(createPlaybackRequestId(null), /^\d+-[a-z0-9]+$/);
});
