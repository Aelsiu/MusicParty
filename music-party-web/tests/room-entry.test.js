import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolveRoomEntry } from '../src/services/roomEntry.js';
import { roomIdFromPath, navigateRoom, consumeRoomPairingCode } from '../src/services/roomRoute.js';

const id = 'M9vkDrTt';
const room = { id, name: '音乐房间', publicRoom: false };
const forbidden = () => Object.assign(new Error('denied'), { response: { status: 403 } });
function scenario(publicRoom = false) {
    const calls = [];
    const api = {
        access: async value => { calls.push(['access', value]); return { ...room, publicRoom }; },
        manage: async value => { calls.push(['manage', value]); return room; },
        resume: async (value, token) => { calls.push(['resume', value, token]); return { room, expiresAt: 1000 }; },
        publicAdmission: async value => { calls.push(['public', value]); return { room: { ...room, publicRoom: true }, token: 'public-token', expiresAt: 1000 }; },
        joinRoom: async (value, code) => { calls.push(['join', value, code]); return { room, token: 'pairing-token', expiresAt: 1000 }; }
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

test('a supplied invitation submits its normalized pairing code before all saved or public entry', async () => {
    const { api, calls } = scenario(true);
    api.login = () => { throw new Error('Query must never verify a license'); };
    const result = await resolveRoomEntry(id, { api, pairingCode: 'A0zB', managerToken: 'manager',
        admission: { token: 'old', expiresAt: 1000 }, now: 10 });
    assert.equal(result.kind, 'member'); assert.equal(result.token, 'pairing-token');
    assert.deepEqual(calls, [['access', id], ['join', id, 'a0zb']]);
});

test('invalid invitations stay on NEED CODE and cannot fall back to a saved owner or public room', async () => {
    for (const code of ['', 'abcdx', ' abc', '许可密钥', 'ROOT-KEY-1234']) {
        const { api, calls } = scenario(true);
        const result = await resolveRoomEntry(id, { api, pairingCode: code, managerToken: 'manager',
            admission: { token: 'old', expiresAt: 1000 }, now: 10 });
        assert.equal(result.kind, 'code'); assert.equal(result.room.id, id); assert.match(result.error, /配对码/);
        assert.deepEqual(calls, [['access', id]]);
    }
});

test('expired invitations expose the code form and server error without using another admission', async () => {
    const { api, calls } = scenario(true);
    api.joinRoom = async (value, code) => { calls.push(['join', value, code]);
        throw { response: { status: 403, data: { message: '配对码已更新' } } }; };
    const result = await resolveRoomEntry(id, { api, pairingCode: 'old0', managerToken: 'manager',
        admission: { token: 'old', expiresAt: 1000 }, now: 10 });
    assert.equal(result.kind, 'code'); assert.equal(result.error, '配对码已更新');
    assert.deepEqual(calls, [['access', id], ['join', id, 'old0']]);
});

test('an invitation never resolves from root and network failure remains retryable', async () => {
    const { api, calls } = scenario();
    assert.equal((await resolveRoomEntry('', { api, pairingCode: '1234' })).kind, 'security');
    assert.deepEqual(calls, []);
    const offline = new Error('offline');
    api.joinRoom = async () => { throw offline; };
    await assert.rejects(resolveRoomEntry(id, { api, pairingCode: 'abcd' }), offline);
});

test('changing the route during lookup stops invitation and saved credential requests', async () => {
    const { api, calls } = scenario(); let active = true;
    api.access = async value => { calls.push(['access', value]); active = false; return room; };
    const result = await resolveRoomEntry(id, { api, pairingCode: 'abcd', managerToken: 'manager', isCurrent: () => active });
    assert.equal(result.kind, 'cancelled'); assert.deepEqual(calls, [['access', id]]);
});

test('changing the route while joining discards the invitation result', async () => {
    const { api } = scenario(); let active = true;
    api.joinRoom = async () => { active = false; return { room, token: 'never-saved', expiresAt: 1000 }; };
    assert.equal((await resolveRoomEntry(id, { api, pairingCode: 'abcd', isCurrent: () => active })).kind, 'cancelled');
});

function routeBrowser(pathname = '/' + id, search = '?pcd=A0zB', hash = '#fragment') {
    const replacements = [];
    const browser = { location: { pathname, search, hash }, history: { state: { keep: true },
        replaceState: (state, _title, path) => { replacements.push({ state, path });
            browser.location = { pathname: path, search: '', hash: '' }; } } };
    return { browser, replacements };
}

test('invitation codes are consumed once and canonicalized to the clean room path', () => {
    const { browser, replacements } = routeBrowser('/' + id + '/', '?other=1&pcd=A0zB');
    assert.equal(consumeRoomPairingCode(id, browser), 'A0zB');
    assert.equal(consumeRoomPairingCode(id, browser), undefined);
    assert.deepEqual(replacements, [{ state: { keep: true }, path: '/' + id }]);
});

test('empty or duplicate invitations are erased and treated as invalid code submissions', () => {
    for (const query of ['?pcd=', '?pcd=abcd&pcd=1234']) {
        const { browser, replacements } = routeBrowser('/' + id, query);
        assert.equal(consumeRoomPairingCode(id, browser), ''); assert.equal(replacements.length, 1);
    }
});

test('root, endpoint, other-room and license queries are never consumed as room invitations', () => {
    for (const [path, query, expected] of [['/', '?pcd=abcd', ''], ['/api/rooms', '?pcd=abcd', ''],
        ['/abcdefgh', '?pcd=abcd', id], ['/' + id, '?key=LICENSE', id]]) {
        const { browser, replacements } = routeBrowser(path, query);
        assert.equal(consumeRoomPairingCode(expected, browser), undefined); assert.deepEqual(replacements, []);
    }
});

test('entering an already matching room cleans remaining query and hash with replaceState', () => {
    const { browser, replacements } = routeBrowser(); const events = [];
    browser.dispatchEvent = event => events.push(event.type);
    browser.history.pushState = () => { throw new Error('Must replace the same room URL'); };
    navigateRoom(id, browser); navigateRoom(id, browser);
    assert.deepEqual(replacements, [{ state: null, path: '/' + id }]);
    assert.deepEqual(events, ['musicparty:route']);
});
