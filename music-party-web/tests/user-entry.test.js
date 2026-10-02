import assert from 'node:assert/strict';
import { beforeEach, test } from 'node:test';
import { createPinia, setActivePinia } from 'pinia';
import { STORAGE_KEYS } from '../src/constants/keys.js';

const storage = new Map();
globalThis.localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key)
};
const session = new Map();
globalThis.sessionStorage = {
    getItem: key => session.get(key) ?? null,
    setItem: (key, value) => session.set(key, String(value)),
    removeItem: key => session.delete(key)
};
const { useUserStore } = await import('../src/stores/user.js');
let user;

beforeEach(() => {
    storage.clear();
    localStorage.setItem(STORAGE_KEYS.USERNAME, 'Alice');
    localStorage.setItem(STORAGE_KEYS.TOKEN, 'alice-token');
    setActivePinia(createPinia());
    user = useUserStore();
    user.userToken = 'alice-token';
});

test('entering with the remembered ID keeps the existing identity', () => {
    user.updateBinding('netease', 'netease-alice', 'Alice Music', 'alice.png');
    user.prepareEntry('Alice', '1234');
    assert.equal(user.userToken, 'alice-token');
    assert.equal(user.currentUser.name, 'Alice');
    assert.equal(user.bindings.netease, 'netease-alice');
});

test('a different entry ID creates a new identity and saves it only after server confirmation', () => {
    user.updateBinding('netease', 'netease-alice', 'Alice Music', 'alice.png');
    user.updateBinding('bilibili', 'bilibili-alice', 'Alice Video', 'alice-video.png');
    user.prepareEntry('Bob', '1234');
    const newToken = user.userToken;
    assert.notEqual(newToken, 'alice-token');
    assert.equal(user.currentUser.name, 'Bob');
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), 'alice-token');
    assert.equal(localStorage.getItem(STORAGE_KEYS.USERNAME), 'Alice');
    assert.deepEqual(user.bindings, {});
    for (const key of [STORAGE_KEYS.BINDINGS, STORAGE_KEYS.NETEASE_USERNAME, STORAGE_KEYS.NETEASE_AVATAR, STORAGE_KEYS.BILIBILI_USERNAME, STORAGE_KEYS.BILIBILI_AVATAR]) {
        assert.equal(localStorage.getItem(key), null, key);
    }

    user.initUser('bob-session', 'Bob', false);
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), newToken);
    assert.equal(localStorage.getItem(STORAGE_KEYS.USERNAME), 'Bob');
    user.prepareEntry('Bob', '1234');
    assert.equal(user.userToken, newToken);
});

test('first entry without a remembered ID starts without a stale binding', () => {
    user.updateBinding('netease', 'stale-user', 'Stale User', 'stale.png');
    localStorage.removeItem(STORAGE_KEYS.USERNAME);
    user.prepareEntry('Carol', '1234');
    assert.deepEqual(user.bindings, {});
    assert.equal(localStorage.getItem(STORAGE_KEYS.BINDINGS), null);
});

test('renaming inside the room updates the remembered ID without replacing identity', () => {
    user.prepareEntry('Alice', '1234');
    user.initUser('alice-session', 'Renamed Alice', false);
    assert.equal(user.userToken, 'alice-token');
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), 'alice-token');
    assert.equal(localStorage.getItem(STORAGE_KEYS.USERNAME), 'Renamed Alice');
    user.prepareEntry('Renamed Alice', '1234');
    assert.equal(user.userToken, 'alice-token');
});
test('a shared profile refresh updates another room without restoring its old binding', () => {
    user.initUser('alice-session', 'Alice', false);
    user.updateBinding('netease', 'old-account', 'Old Account', 'old.png');
    user.syncProfile({ name: 'Renamed Alice', bindings: { netease: 'new-account', bilibili: 'shared-video' } });
    assert.equal(user.currentUser.name, 'Renamed Alice');
    assert.equal(user.bindings.netease, 'new-account');
    assert.equal(user.neteaseAvatar, '');
    assert.equal(user.bindings.bilibili, 'shared-video');
    assert.equal(user.userToken, 'alice-token');
});
test('returning before CONNECT restores the remembered profile token when using its name again', () => {
    user.prepareEntry('Bob', '');
    assert.notEqual(user.userToken, 'alice-token');
    user.resetAuthentication();
    user.prepareEntry('Alice', '');
    assert.equal(user.userToken, 'alice-token');
});
test('returning clears a pending name prompt and its old room callback', () => {
    let called = false;
    user.showNameModal = true; user.renameError = 'old error';
    user.setPostNameAction(() => { called = true; });
    user.resetAuthentication();
    assert.equal(user.showNameModal, false); assert.equal(user.renameError, '');
    user.isGuest = true;
    user.initUser('new-session', 'Alice', false);
    assert.equal(called, false);
});
test('an explicitly anonymous direct entry persists its new identity only after connection', () => {
    user.prepareEntry('游客', '');
    const guestToken = user.userToken;
    assert.notEqual(guestToken, 'alice-token');
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), 'alice-token');
    user.initUser('guest-session', '游客', true);
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), guestToken);
    assert.equal(localStorage.getItem(STORAGE_KEYS.USERNAME), null);
});
test('choosing an entry name after guest listening creates a separate identity', () => {
    localStorage.removeItem(STORAGE_KEYS.USERNAME);
    user.currentUser.name = '游客'; user.isGuest = true;
    user.prepareEntry('Carol', '');
    assert.notEqual(user.userToken, 'alice-token');
    assert.equal(localStorage.getItem(STORAGE_KEYS.TOKEN), 'alice-token');
    user.initUser('carol-session', 'Carol', false);
    assert.equal(localStorage.getItem(STORAGE_KEYS.USERNAME), 'Carol');
});
