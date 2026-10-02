import assert from 'node:assert/strict';
import { test } from 'node:test';
import { verifyRoomOwner } from '../src/services/adminVerification.js';

function verification() {
    const connection = { roomId: 'room-a', roomToken: 'existing-admission', sessionId: 'live-session' };
    const calls = [];
    let current = true;
    const candidate = { token: 'candidate-management-token', licenseId: 'license-a', root: false };
    const dependencies = {
        connection,
        isCurrent: () => current,
        login: async key => { calls.push(['login', key]); return candidate; },
        promote: async (snapshot, token) => { calls.push(['promote', snapshot, token]); return { role: 'OWNER' }; },
        commit: session => { calls.push(['commit', session]); }
    };
    return { dependencies, calls, candidate, leave: () => { current = false; } };
}

test('room owner verification upgrades the same live connection before retaining management access', async () => {
    for (const role of ['OWNER', 'ROOT']) {
        const h = verification();
        h.dependencies.promote = async (connection, token) => {
            h.calls.push(['promote', connection, token]);
            assert.equal(h.calls.some(([action]) => action === 'commit'), false);
            return { role };
        };
        assert.equal(await verifyRoomOwner('license-key', h.dependencies), h.candidate);
        assert.deepEqual(h.calls, [
            ['login', 'license-key'], ['promote', h.dependencies.connection, h.candidate.token],
            ['commit', h.candidate]
        ]);
    }
});

test('invalid license, wrong room ownership and rejected promotion never retain candidate credentials', async () => {
    for (const failedStage of ['login', 'promote']) {
        const h = verification();
        h.dependencies[failedStage] = async () => { throw new Error('无权管理该房间'); };
        await assert.rejects(verifyRoomOwner('unrelated-key', h.dependencies), /无权管理/);
        assert.equal(h.calls.some(([action]) => action === 'commit'), false);
        if (failedStage === 'login') assert.deepEqual(h.calls, []);
    }
});

test('a missing or disconnected live session does not consume a license login attempt', async () => {
    for (const invalid of ['missingRoom', 'missingSession', 'disconnected']) {
        const h = verification();
        if (invalid === 'missingRoom') h.dependencies.connection.roomId = '';
        if (invalid === 'missingSession') h.dependencies.connection.sessionId = '';
        if (invalid === 'disconnected') h.leave();
        await assert.rejects(verifyRoomOwner('key', h.dependencies), /房间连接已改变/);
        assert.deepEqual(h.calls, []);
    }
});

test('leaving or reconnecting during license login cancels promotion and persistence', async () => {
    const h = verification();
    let finishLogin;
    h.dependencies.login = () => new Promise(resolve => { finishLogin = resolve; });
    const result = verifyRoomOwner('key', h.dependencies);
    h.leave();
    finishLogin(h.candidate);
    await assert.rejects(result, /房间连接已改变/);
    assert.deepEqual(h.calls, []);
});

test('leaving or reconnecting during server promotion cannot grant the next room owner access', async () => {
    const h = verification();
    let finishPromotion;
    h.dependencies.promote = () => new Promise(resolve => { finishPromotion = resolve; });
    const result = verifyRoomOwner('key', h.dependencies);
    await Promise.resolve();
    h.leave();
    finishPromotion({ role: 'OWNER' });
    await assert.rejects(result, /房间连接已改变/);
    assert.deepEqual(h.calls, [['login', 'key']]);
});

test('an unexpected promotion response never grants management access', async () => {
    for (const result of [undefined, {}, { role: 'USER' }]) {
        const h = verification();
        h.dependencies.promote = async () => result;
        await assert.rejects(verifyRoomOwner('key', h.dependencies), /权限升级失败/);
        assert.equal(h.calls.some(([action]) => action === 'commit'), false);
    }
});
