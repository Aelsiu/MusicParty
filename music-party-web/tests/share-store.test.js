import assert from 'node:assert/strict';
import { beforeEach, test } from 'node:test';
import { register } from 'node:module';
import { createPinia, setActivePinia } from 'pinia';

// Vite resolves the existing extensionless source imports; keep Node tests on
// those same modules rather than replacing the store with a copied fixture.
register(new URL('./helpers/source-resolution.js', import.meta.url));
function storage() {
    const values = new Map();
    return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) };
}
globalThis.localStorage = storage();
globalThis.sessionStorage = storage();
const { useChatStore } = await import('../src/stores/chat.js');
const { useUserStore } = await import('../src/stores/user.js');
const { roomSession, selectRoom, clearRoom, syncRoomShare } = await import('../src/services/roomSession.js');
const { roomsApi } = await import('../src/api/rooms.js');
const { default: client } = await import('../src/api/client.js');
const { socketService } = await import('../src/services/socket.js');
const inviteRoom = roomsApi.invite;
function deferred() { let resolve; return { promise: new Promise(done => { resolve = done; }), resolve }; }

let chat, user, events, copied, sent, invited, updates, requests;
function enterUser() { roomSession.ownerAccess = false; roomSession.managerToken = ''; }
beforeEach(() => {
    clearRoom(false);
    events = []; copied = []; sent = []; invited = []; updates = []; requests = [];
    globalThis.window = {
        location: { origin: 'https://music.example', pathname: '/abcdefgh', search: '', hash: '' },
        history: { pushState() {}, replaceState() {} },
        dispatchEvent: event => { events.push(event); }
    };
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText: async value => copied.push(value) } } });
    setActivePinia(createPinia());
    user = useUserStore();
    user.isAuthPassed = true; user.isGuest = false;
    roomSession.managerToken = 'manager';
    selectRoom({ id: 'abcdefgh', name: 'Music', shareEnabled: true }, 'room-token', true);
    roomsApi.invite = async id => { invited.push(id); return { pairingCode: 'Ab12' }; };
    roomsApi.setShareEnabled = async (id, enabled) => { updates.push([id, enabled]); return { shareEnabled: enabled }; };
    client.defaults.adapter = async config => {
        requests.push(config);
        return { data: { pairingCode: 'Ab12' }, status: 200, statusText: 'OK', headers: {}, config };
    };
    socketService.send = (...args) => { sent.push(args); return true; };
    chat = useChatStore();
});

test('bare share success emits one local animation event after copy and private feedback', async () => {
    const expected = { roomId: roomSession.roomId, generation: roomSession.generation, shareRevision: roomSession.shareRevision };
    assert.equal(await chat.runShareCommand(), true);
    assert.deepEqual(copied, ['https://music.example/abcdefgh?pcd=ab12']);
    const inviteEvents = events.filter(event => event.type === 'musicparty:invite-copied');
    assert.equal(inviteEvents.length, 1);
    assert.deepEqual(inviteEvents[0].detail, expected);
    assert.equal(chat.messages.at(-1).content, '邀请链接已复制');
    assert.equal(chat.messages.at(-1).private, true);
    assert.deepEqual(sent, []);
});

test('User can copy through the room invite API without management credentials', async () => {
    enterUser();
    roomsApi.invite = inviteRoom;
    assert.equal(await chat.runShareCommand(), true);
    assert.deepEqual(copied, ['https://music.example/abcdefgh?pcd=ab12']);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/rooms/abcdefgh/invite');
    assert.equal(requests[0].headers.get('X-Room-Token'), 'room-token');
    assert.equal(requests[0].headers.has('Authorization'), false);
    assert.equal(chat.messages.at(-1).content, '邀请链接已复制');
    assert.equal(chat.messages.at(-1).private, true);
    assert.equal(events.filter(event => event.type === 'musicparty:invite-copied').length, 1);
    assert.deepEqual(sent, []);
});

test('a saved unrelated manager session does not replace User admission for invite requests', async () => {
    enterUser();
    roomSession.managerToken = 'other-room-manager';
    roomsApi.invite = inviteRoom;
    assert.equal(await chat.runShareCommand(), true);
    assert.equal(requests[0].headers.get('X-Room-Token'), 'room-token');
    assert.equal(requests[0].headers.has('Authorization'), false);
});

test('manager invite requests keep their management authorization', async () => {
    roomsApi.invite = inviteRoom;
    assert.equal(await chat.runShareCommand(), true);
    assert.equal(requests[0].headers.get('Authorization'), 'Bearer manager');
});

test('User cannot copy when sharing is closed and no invitation request starts', async () => {
    enterUser();
    syncRoomShare(false);
    assert.equal(await chat.runShareCommand(), false);
    assert.deepEqual(invited, []);
    assert.deepEqual(copied, []);
    assert.equal(chat.messages.at(-1).content, '房间邀请分享已关闭');
    assert.equal(chat.messages.at(-1).private, true);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
    assert.deepEqual(sent, []);
});

test('User on and off commands are denied before any management request', async () => {
    enterUser();
    for (const parameter of ['on', 'off']) assert.equal(await chat.runShareCommand(parameter), false);
    assert.deepEqual(updates, []);
    assert.deepEqual(invited, []);
    assert.deepEqual(copied, []);
    assert.equal(roomSession.shareEnabled, true);
    assert.equal(chat.messages.length, 2);
    assert.equal(chat.messages.every(message => message.private && /仅限本房间 Owner 或 Root/.test(message.content)), true);
    assert.deepEqual(sent, []);
});

test('guest and disconnected identities cannot execute share commands', async () => {
    enterUser();
    user.isGuest = true;
    for (const parameter of ['', 'on', 'off']) assert.equal(await chat.runShareCommand(parameter), false);
    user.isGuest = false; user.isAuthPassed = false;
    assert.equal(await chat.runShareCommand(), false);
    assert.deepEqual(invited, []);
    assert.deepEqual(updates, []);
    assert.deepEqual(copied, []);
    assert.equal(chat.messages.length, 0);
});

test('SHARE_TRIGGER entry point shares the same successful animation event path', async () => {
    assert.equal(await chat.shareInvite(), true);
    assert.equal(events.filter(event => event.type === 'musicparty:invite-copied').length, 1);
    assert.equal(copied.length, 1);
    assert.deepEqual(sent, []);
});

test('clipboard failure never triggers the copied animation event', async () => {
    navigator.clipboard.writeText = async () => { throw new Error('Denied'); };
    assert.equal(await chat.shareInvite(), false);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
    assert.equal(chat.messages.at(-1).private, true);
    assert.match(chat.messages.at(-1).content, /无法访问剪贴板/);
});

test('leaving during the fresh invitation request cancels copying and animation feedback', async () => {
    let finish;
    roomsApi.invite = () => new Promise(resolve => { finish = resolve; });
    const pending = chat.shareInvite();
    await Promise.resolve();
    clearRoom(false);
    finish({ pairingCode: '1234' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, []);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
    assert.equal(chat.messages.length, 0);
});

test('manager sharing settings and denied User settings do not emit a copied animation event', async () => {
    assert.equal(await chat.runShareCommand('off'), true);
    assert.equal(roomSession.shareEnabled, false);
    assert.equal(await chat.runShareCommand(), false);
    assert.equal(await chat.runShareCommand('on'), true);
    roomSession.ownerAccess = false;
    assert.equal(await chat.runShareCommand('off'), false);
    assert.deepEqual(updates, [['abcdefgh', false], ['abcdefgh', true]]);
    assert.deepEqual(copied, []);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
    assert.equal(chat.messages.every(message => message.private), true);
    assert.deepEqual(sent, []);
});

test('User invite copying survives changes to unused management credentials', async () => {
    enterUser();
    const request = deferred();
    roomsApi.invite = () => request.promise;
    const pending = chat.runShareCommand();
    await Promise.resolve();
    roomSession.managerToken = 'new-management-session';
    request.resolve({ pairingCode: 'a001' });
    assert.equal(await pending, true);
    assert.deepEqual(copied, ['https://music.example/abcdefgh?pcd=a001']);
});

test('User room token changes cancel pending copying and private feedback', async () => {
    enterUser();
    const request = deferred();
    roomsApi.invite = () => request.promise;
    const pending = chat.runShareCommand();
    await Promise.resolve();
    roomSession.roomToken = 'replacement-room-token';
    request.resolve({ pairingCode: 'a001' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, []);
    assert.equal(chat.messages.length, 0);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
});

test('closing and reopening sharing cancels the original User invitation request', async () => {
    enterUser();
    const request = deferred();
    roomsApi.invite = () => request.promise;
    const pending = chat.runShareCommand();
    await Promise.resolve();
    syncRoomShare(false); syncRoomShare(true);
    request.resolve({ pairingCode: 'a001' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, []);
    assert.equal(chat.messages.length, 0);
    assert.equal(events.some(event => event.type === 'musicparty:invite-copied'), false);
});

test('new User room sessions can copy while the old room request is still pending', async () => {
    enterUser();
    const request = deferred();
    roomsApi.invite = id => id === 'abcdefgh' ? request.promise : Promise.resolve({ pairingCode: 'b002' });
    const pending = chat.runShareCommand();
    await Promise.resolve();
    selectRoom({ id: 'new-room', name: 'New room', shareEnabled: true }, 'new-room-token');
    assert.equal(await chat.runShareCommand(), true);
    request.resolve({ pairingCode: 'a001' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, ['https://music.example/new-room?pcd=b002']);
    assert.equal(chat.messages.length, 1);
    const inviteEvents = events.filter(event => event.type === 'musicparty:invite-copied');
    assert.equal(inviteEvents.length, 1);
    assert.equal(inviteEvents[0].detail.roomId, 'new-room');
});

test('a User becoming a guest invalidates pending clipboard feedback', async () => {
    enterUser();
    const request = deferred();
    roomsApi.invite = () => request.promise;
    const pending = chat.runShareCommand();
    await Promise.resolve();
    user.isGuest = true;
    request.resolve({ pairingCode: 'a001' });
    assert.equal(await pending, false);
    assert.deepEqual(copied, []);
    assert.equal(chat.messages.length, 0);
});

test('revoked manager access cannot apply a pending share setting response', async () => {
    const request = deferred();
    roomsApi.setShareEnabled = () => request.promise;
    const pending = chat.runShareCommand('off');
    enterUser();
    request.resolve({ shareEnabled: false });
    assert.equal(await pending, false);
    assert.equal(roomSession.shareEnabled, true);
    assert.equal(chat.messages.length, 0);
    assert.deepEqual(copied, []);
});

test('manager token replacement cannot apply a pending share setting response', async () => {
    const request = deferred();
    roomsApi.setShareEnabled = () => request.promise;
    const pending = chat.runShareCommand('off');
    roomSession.managerToken = 'replacement-manager';
    request.resolve({ shareEnabled: false });
    assert.equal(await pending, false);
    assert.equal(roomSession.shareEnabled, true);
    assert.equal(chat.messages.length, 0);
});
