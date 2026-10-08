import test from 'node:test';
import assert from 'node:assert/strict';
import { systemConfigDraft, systemConfigUpdate } from './systemConfig.js';

const snapshot = {
    maxQueueSize: 200,
    maxHistorySize: 0,
    maxUserSongs: 20,
    maxPlaylistImportSize: 100,
    maxChatHistorySize: 0,
    minChatIntervalMs: 0,
    maxChatMessageLength: 1000,
    bilibiliMaxDurationMinutes: 120,
    videoMaxDurationMinutes: 120,
    pairingIntervalMinutes: 10,
    neteaseQuality: 'hires',
    allowSeek: true
};

test('global system edits contain independent video duration and use API queue/history aliases', () => {
    const draft = systemConfigDraft(snapshot);
    assert.equal(Object.keys(draft).length, 11);
    assert.equal(draft.neteaseQuality, undefined);
    assert.equal(draft.allowSeek, undefined);
    assert.deepEqual(systemConfigUpdate(draft), {
        maxSize: 200, historySize: 0, maxUserSongs: 20, maxPlaylistImportSize: 100,
        maxChatHistorySize: 0, minChatIntervalMs: 0, maxChatMessageLength: 1000,
        bilibiliMaxDurationMinutes: 120, videoMaxDurationMinutes: 120, pairingIntervalMinutes: 10, videoMaxResolution: 'UNLIMITED'
    });
});

test('global system settings accept all lower and upper boundaries', () => {
    assert.deepEqual(systemConfigUpdate({
        maxQueueSize: 1, maxHistorySize: 0, maxUserSongs: 1, maxPlaylistImportSize: 1,
        maxChatHistorySize: 0, minChatIntervalMs: 0, maxChatMessageLength: 1,
        bilibiliMaxDurationMinutes: 1, videoMaxDurationMinutes: 1, pairingIntervalMinutes: 1
    }), {
        maxSize: 1, historySize: 0, maxUserSongs: 1, maxPlaylistImportSize: 1,
        maxChatHistorySize: 0, minChatIntervalMs: 0, maxChatMessageLength: 1,
        bilibiliMaxDurationMinutes: 1, videoMaxDurationMinutes: 1, pairingIntervalMinutes: 1, videoMaxResolution: 'UNLIMITED'
    });
    assert.deepEqual(systemConfigUpdate({
        maxQueueSize: 10000, maxHistorySize: 10000, maxUserSongs: 10000, maxPlaylistImportSize: 10000,
        maxChatHistorySize: 100000, minChatIntervalMs: 600000, maxChatMessageLength: 10000,
        bilibiliMaxDurationMinutes: 1440, videoMaxDurationMinutes: 1440, pairingIntervalMinutes: 60
    }), {
        maxSize: 10000, historySize: 10000, maxUserSongs: 10000, maxPlaylistImportSize: 10000,
        maxChatHistorySize: 100000, minChatIntervalMs: 600000, maxChatMessageLength: 10000,
        bilibiliMaxDurationMinutes: 1440, videoMaxDurationMinutes: 1440, pairingIntervalMinutes: 60, videoMaxResolution: 'UNLIMITED'
    });
});

test('global system edits reject missing, fractional, nonnumeric, and out-of-range values', () => {
    for (const value of [undefined, '', '20', 1.5, NaN, Infinity, 0, 10001]) {
        assert.throws(() => systemConfigUpdate({ ...snapshot, maxQueueSize: value }), /队列最大长度/);
    }
    for (const [field, value, label] of [
        ['maxHistorySize', -1, '历史记录容量'],
        ['maxUserSongs', 0, '用户点歌上限'],
        ['maxPlaylistImportSize', 10001, '导入单次上限'],
        ['maxChatHistorySize', 100001, '聊天记录容量'],
        ['minChatIntervalMs', 600001, '发言频率限制'],
        ['maxChatMessageLength', 0, '消息最大长度'],
        ['bilibiliMaxDurationMinutes', 1441, 'B站时长上限'],
        ['videoMaxDurationMinutes', 0, '视频时长上限'],
        ['videoMaxDurationMinutes', 1441, '视频时长上限'],
        ['pairingIntervalMinutes', 0, '配对码更新周期'],
        ['pairingIntervalMinutes', 61, '配对码更新周期'],
        ['pairingIntervalMinutes', 1.5, '配对码更新周期']
    ]) assert.throws(() => systemConfigUpdate({ ...snapshot, [field]: value }), new RegExp(label));
});

test('old global configuration uses independent 120 minute video default', () => {
    const { videoMaxDurationMinutes, ...old } = snapshot;
    assert.equal(systemConfigDraft(old).videoMaxDurationMinutes, 120);
    assert.equal(systemConfigDraft(old).bilibiliMaxDurationMinutes, old.bilibiliMaxDurationMinutes);
});

test('resolution cap accepts known values atomically and old snapshots default unlimited', () => {
 assert.equal(systemConfigDraft(snapshot).videoMaxResolution, 'UNLIMITED');
 for (const cap of ['UNLIMITED','480P','720P','1080P','1440P','2160P']) assert.equal(systemConfigUpdate({...snapshot,videoMaxResolution:cap}).videoMaxResolution,cap);
 assert.throws(() => systemConfigUpdate({...snapshot,videoMaxResolution:'360P'}), /分辨率/);
});
