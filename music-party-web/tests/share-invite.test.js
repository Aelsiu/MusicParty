import assert from 'node:assert/strict';
import { test } from 'node:test';
import { copyRoomInvite, isShareCommand, roomInviteUrl } from '../src/services/shareInvite.js';
import { copyAsyncText } from '../src/utils/clipboard.js';

function deferred() {
    let resolve;
    return { promise: new Promise(done => { resolve = done; }), resolve };
}

function request(overrides = {}) {
    const notices = [], copied = [], fetched = [];
    const dependencies = {
        roomId: 'abcdefgh', origin: 'https://music.example:8443',
        invite: async id => { fetched.push(id); return { pairingCode: 'a01Z' }; },
        copy: async value => copied.push(await value),
        notify: text => notices.push(text),
        ...overrides
    };
    return { dependencies, notices, copied, fetched };
}

test('share recognizes only its own command and is case insensitive', () => {
    for (const value of ['//share', ' //SHARE ', '//share on', '//share off extra', '//share\non']) {
        assert.equal(isShareCommand(value), true);
    }
    for (const value of ['share', '//shares', '//shareable', 'hello //share']) assert.equal(isShareCommand(value), false);
});

test('invite URL has exactly the room path and normalized pairing parameter', () => {
    assert.equal(roomInviteUrl('https://music.example/path?old=1#hash', 'abcdefgh', 'A01z'), 'https://music.example/abcdefgh?pcd=a01z');
    for (const code of ['', 'ABC', 'ABCD5', 'ab_1', 'ａｂ12', 1234, ' a01']) {
        assert.throws(() => roomInviteUrl('https://music.example', 'abcdefgh', code));
    }
});

test('each copy fetches the current pairing code and preserves leading zeroes', async () => {
    const { dependencies, notices, copied, fetched } = request();
    assert.equal(await copyRoomInvite(dependencies), true);
    dependencies.invite = async id => { fetched.push(id); return { pairingCode: '000b' }; };
    assert.equal(await copyRoomInvite(dependencies), true);
    assert.deepEqual(copied, ['https://music.example:8443/abcdefgh?pcd=a01z', 'https://music.example:8443/abcdefgh?pcd=000b']);
    assert.deepEqual(fetched, ['abcdefgh', 'abcdefgh']);
    assert.deepEqual(notices, ['邀请链接已复制', '邀请链接已复制']);
});

test('disabled or obsolete room state avoids both API and clipboard calls', async () => {
    const { dependencies, notices, copied, fetched } = request({ isCurrent: () => false });
    assert.equal(await copyRoomInvite(dependencies), false);
    assert.deepEqual({ notices, copied, fetched }, { notices: [], copied: [], fetched: [] });
});

test('revoked sharing API never copies a link or reports success', async () => {
    const { dependencies, notices, copied } = request({
        invite: async () => { throw { response: { data: { message: '房间邀请分享已关闭' } } }; }
    });
    assert.equal(await copyRoomInvite(dependencies), false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, ['房间邀请分享已关闭']);
});

test('malformed fresh pairing code is rejected before clipboard resolves', async () => {
    const { dependencies, notices, copied } = request({ invite: async () => ({ pairingCode: '-----' }) });
    assert.equal(await copyRoomInvite(dependencies), false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, ['邀请链接获取失败，请重试']);
});

test('copy is cancelled without feedback when sharing closes during the fresh request', async () => {
    const pending = deferred();
    let active = true;
    const { dependencies, notices, copied } = request({ invite: () => pending.promise, isCurrent: () => active });
    const operation = copyRoomInvite(dependencies);
    await Promise.resolve();
    active = false;
    pending.resolve({ pairingCode: '1234' });
    assert.equal(await operation, false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, []);
});

test('a closed then reopened flag generation invalidates the original copy', async () => {
    const pending = deferred();
    let generation = 1;
    const { dependencies, notices, copied } = request({ invite: () => pending.promise, isCurrent: () => generation === 1 });
    const operation = copyRoomInvite(dependencies);
    await Promise.resolve();
    generation += 2;
    pending.resolve({ pairingCode: '1234' });
    assert.equal(await operation, false);
    assert.deepEqual(copied, []);
    assert.deepEqual(notices, []);
});

test('switching room during an in-flight clipboard write never reports copied', async () => {
    const pending = deferred(), started = deferred();
    let active = true;
    const { dependencies, notices } = request({ copy: async value => { await value; started.resolve(); await pending.promise; }, isCurrent: () => active });
    const operation = copyRoomInvite(dependencies);
    await started.promise;
    active = false;
    pending.resolve();
    assert.equal(await operation, false);
    assert.deepEqual(notices, []);
});

test('clipboard rejection reports a local failure after request handling', async () => {
    const { dependencies, notices } = request({ copy: async () => { throw new Error('Denied'); } });
    assert.equal(await copyRoomInvite(dependencies), false);
    assert.deepEqual(notices, ['无法访问剪贴板，请允许浏览器复制后重试']);
});

test('API rejection has priority over simultaneous clipboard rejection', async () => {
    const { dependencies, notices } = request({
        copy: async () => { throw new Error('Denied'); },
        invite: async () => { throw { response: { data: { detail: '房间访问已失效' } } }; }
    });
    assert.equal(await copyRoomInvite(dependencies), false);
    assert.deepEqual(notices, ['房间访问已失效']);
});

test('success waits for both fresh code and completed clipboard write', async () => {
    const pending = deferred();
    const { dependencies, notices } = request({ copy: async () => pending.promise });
    const operation = copyRoomInvite(dependencies);
    assert.deepEqual(notices, []);
    pending.resolve();
    assert.equal(await operation, true);
    assert.deepEqual(notices, ['邀请链接已复制']);
});

test('clipboard write starts in the gesture before the invite request resolves', async () => {
    const pending = deferred();
    let started = false, copied;
    class ClipboardItem { constructor(data) { this.data = data; } }
    const { dependencies } = request({
        invite: () => pending.promise,
        copy: value => copyAsyncText(value, {
            ClipboardItem,
            clipboard: { write: async ([item]) => { started = true; copied = await (await item.data['text/plain']).text(); } }
        })
    });
    const operation = copyRoomInvite(dependencies);
    assert.equal(started, true);
    pending.resolve({ pairingCode: 'Ab12' });
    assert.equal(await operation, true);
    assert.equal(copied, 'https://music.example:8443/abcdefgh?pcd=ab12');
});
