import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolveRoomEntry } from '../src/services/roomEntry.js';
import { roomIdFromPath, navigateRoom } from '../src/services/roomRoute.js';

const id = 'M9vkDrTt';
const room = { id, name: '音乐房间', publicRoom: false };
const forbidden = () => Object.assign(new Error('denied'), { response: { status: 403 } });
function scenario(publicRoom = false) {
    const calls = [];
    const api = {
        access: async value => { calls.push(['access', value]); return { ...room, publicRoom }; },
        manage: async value => { calls.push(['manage', value]); return room; },
        resume: async (value, token) => { calls.push(['resume', value, token]); return { room, expiresAt: 1000 }; },
        publicAdmission: async value => { calls.push(['public', value]); return { room: { ...room, publicRoom: true }, token: 'public-token', expiresAt: 1000 }; }
    };
    return { api, calls };
}

test('root address always shows Security Access without consulting previous room credentials', async () => {
    const { api, calls } = scenario();
    const result = await resolveRoomEntry('', { api, managerToken: 'manager', admission: { token: 'old', expiresAt: 1000 }, now: 10 });
    assert.equal(result.kind, 'security');
    assert.deepEqual(calls, []);
});
test('public address obtains a member admission without a nickname or pairing code', async () => {
    const { api, calls } = scenario(true);
    const result = await resolveRoomEntry(id, { api });
    assert.equal(result.kind, 'member'); assert.equal(result.token, 'public-token');
    assert.deepEqual(calls, [['access', id], ['public', id]]);
});
test('private address requires code when there is no valid admission', async () => {
    const { api, calls } = scenario();
    const result = await resolveRoomEntry(id, { api, admission: { token: 'old', expiresAt: 9 }, now: 10 });
    assert.equal(result.kind, 'code'); assert.equal(result.room.id, id);
    assert.deepEqual(calls, [['access', id]]);
});
test('a same-cycle private admission resumes only the addressed room', async () => {
    const { api, calls } = scenario();
    const result = await resolveRoomEntry(id, { api, admission: { token: 'member-token', expiresAt: 1000 }, now: 10 });
    assert.equal(result.kind, 'member'); assert.equal(result.token, 'member-token');
    assert.deepEqual(calls, [['access', id], ['resume', id, 'member-token']]);
});
test('revoked public admission is discarded and a newly private room asks for code', async () => {
    const { api } = scenario(); let discarded = false;
    api.resume = async () => { throw forbidden(); };
    const result = await resolveRoomEntry(id, { api, admission: { token: 'public-token', expiresAt: 1000 }, now: 10, discardAdmission: () => { discarded = true; } });
    assert.equal(result.kind, 'code'); assert.equal(discarded, true);
});
test('valid room management credentials retain Owner admission', async () => {
    const { api, calls } = scenario();
    assert.equal((await resolveRoomEntry(id, { api, managerToken: 'owner-token' })).kind, 'owner');
    assert.deepEqual(calls, [['access', id], ['manage', id]]);
});
test('an unrelated manager can still enter a public room as a member', async () => {
    const { api, calls } = scenario(true); api.manage = async () => { throw forbidden(); };
    const result = await resolveRoomEntry(id, { api, managerToken: 'other-owner' });
    assert.equal(result.kind, 'member'); assert.equal(result.token, 'public-token');
    assert.deepEqual(calls, [['access', id], ['public', id]]);
});
test('a room becoming private between lookup and admission safely requests code', async () => {
    const { api } = scenario(true); api.publicAdmission = async () => { throw forbidden(); };
    assert.equal((await resolveRoomEntry(id, { api })).kind, 'code');
});
test('network failures retain admission state and report connection failure', async () => {
    const { api } = scenario(); const failure = new Error('offline'); let discarded = false;
    api.resume = async () => { throw failure; };
    await assert.rejects(resolveRoomEntry(id, { api, admission: { token: 'member-token', expiresAt: 1000 }, now: 10, discardAdmission: () => { discarded = true; } }), failure);
    assert.equal(discarded, false);
});
test('room paths preserve case and reject endpoint, asset and nested paths', () => {
    assert.equal(roomIdFromPath('/M9vkDrTt'), id);
    assert.equal(roomIdFromPath('/M9vkDrTt/'), id);
    assert.equal(roomIdFromPath('/api12345'), 'api12345');
    for (const path of ['/', '/api/rooms', '/ws', '/vite.svg', '/M9vkDrTt/admin', '/short']) assert.equal(roomIdFromPath(path), '');
});
test('entering and returning update browser history and notify the application only on change', () => {
    const paths = [], events = [];
    const browser = { location: { pathname: '/' }, history: { pushState: (_state, _title, path) => { paths.push(path); browser.location.pathname = path; } }, dispatchEvent: event => events.push(event.type) };
    navigateRoom(id, browser); navigateRoom(id, browser); navigateRoom('', browser);
    assert.deepEqual(paths, ['/' + id, '/']);
    assert.deepEqual(events, ['musicparty:route', 'musicparty:route']);
});
test('canonicalizing a room address with a trailing slash does not trap browser Back', () => {
    let pushed = false, replaced = '';
    const browser = { location: { pathname: '/' + id + '/' }, history: { pushState: () => { pushed = true; }, replaceState: (_state, _title, path) => { replaced = path; } }, dispatchEvent: () => {} };
    navigateRoom(id, browser);
    assert.equal(pushed, false); assert.equal(replaced, '/' + id);
});
