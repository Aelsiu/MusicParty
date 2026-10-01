import assert from 'node:assert/strict';
import { test } from 'node:test';
import { enterManagedRoom } from '../src/services/roomNavigation.js';

function navigation() {
    const calls = [];
    let roomId = 'current', active = true;
    const dependencies = {
        currentRoomId: () => roomId,
        isActive: () => active,
        manage: async id => { calls.push(['manage', id]); return { id, name: '目标房间' }; },
        leave: () => { calls.push(['leave']); roomId = ''; active = false; },
        settle: async () => { calls.push(['settle']); },
        enter: room => { calls.push(['enter', room]); roomId = room.id; active = true; }
    };
    return { calls, dependencies, deactivate: () => { active = false; } };
}

test('selecting the current room preserves playback and does not reauthorize', async () => {
    const { calls, dependencies } = navigation();
    assert.equal(await enterManagedRoom({ id: 'current' }, dependencies), 'current');
    assert.deepEqual(calls, []);
});

test('switching rooms authorizes first, leaves and unmounts before entering CONNECT', async () => {
    const { calls, dependencies } = navigation();
    assert.equal(await enterManagedRoom({ id: 'target' }, dependencies), 'entered');
    assert.deepEqual(calls, [['manage', 'target'], ['leave'], ['settle'], ['enter', { id: 'target', name: '目标房间' }]]);
});

test('a deleted or unauthorized target keeps the current room connected', async () => {
    const { calls, dependencies } = navigation();
    dependencies.manage = async () => { throw new Error('房间已删除'); };
    await assert.rejects(enterManagedRoom({ id: 'target' }, dependencies), /房间已删除/);
    assert.deepEqual(calls, []);
    assert.equal(dependencies.currentRoomId(), 'current');
});

test('leaving while the target is loading cancels a stale switch', async () => {
    const { calls, dependencies, deactivate } = navigation();
    let resolve;
    dependencies.manage = () => new Promise(done => { resolve = done; });
    const switching = enterManagedRoom({ id: 'target' }, dependencies);
    deactivate();
    resolve({ id: 'target' });
    assert.equal(await switching, 'cancelled');
    assert.deepEqual(calls, []);
});
