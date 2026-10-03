import assert from 'node:assert/strict';
import { test } from 'node:test';

function storage() {
    const values = new Map();
    return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
}
globalThis.sessionStorage = storage();
globalThis.localStorage = storage();
const events = [];
globalThis.window = {
    location: { pathname: '/', search: '', hash: '' },
    history: { pushState: (_state, _title, path) => { window.location.pathname = path; }, replaceState: (_state, _title, path) => { window.location.pathname = path; } },
    dispatchEvent: event => { events.push(event); }
};
const { roomSession, selectRoom, clearRoom, syncRoomShare } = await import('../src/services/roomSession.js');

test('room selection takes the persisted sharing flag and leaving clears it', () => {
    clearRoom(false);
    const generation = roomSession.generation;
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: true }, 'room-token');
    assert.equal(roomSession.shareEnabled, true);
    assert.equal(roomSession.generation, generation + 1);
    clearRoom(false);
    assert.equal(roomSession.shareEnabled, false);
    assert.equal(roomSession.roomId, '');
    assert.equal(roomSession.generation, generation + 2);
    selectRoom({ id: 'abcdefgh', name: 'Music' });
    assert.equal(roomSession.shareEnabled, false);
});

test('room status events update sharing and increment its revision only for changes', () => {
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: true });
    const revision = roomSession.shareRevision;
    assert.equal(syncRoomShare(false), true);
    assert.equal(roomSession.shareEnabled, false);
    assert.equal(roomSession.shareRevision, revision + 1);
    assert.equal(syncRoomShare(false), true);
    assert.equal(roomSession.shareRevision, revision + 1);
    assert.equal(syncRoomShare(true), true);
    assert.equal(roomSession.shareRevision, revision + 2);
    assert.equal(events.at(-1).type, 'musicparty:share');
    assert.deepEqual(events.at(-1).detail, { enabled: true, roomId: 'abcdefgh' });
});

test('stale responses cannot restore sharing after returning to the same room', () => {
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: true });
    const oldGeneration = roomSession.generation;
    clearRoom(false);
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: false });
    assert.equal(syncRoomShare(true, 'abcdefgh', oldGeneration), false);
    assert.equal(roomSession.shareEnabled, false);
    assert.equal(syncRoomShare(true, 'otherroom'), false);
    assert.equal(syncRoomShare('true'), false);
    assert.equal(roomSession.shareEnabled, false);
});

test('a late setting response cannot overwrite newer websocket sharing state', () => {
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: false });
    const generation = roomSession.generation, revision = roomSession.shareRevision;
    syncRoomShare(true);
    // The matching websocket message from our own PATCH may precede its HTTP response.
    assert.equal(syncRoomShare(true, 'abcdefgh', generation, revision), true);
    syncRoomShare(false);
    assert.equal(syncRoomShare(true, 'abcdefgh', generation, revision), false);
    assert.equal(roomSession.shareEnabled, false);
});
