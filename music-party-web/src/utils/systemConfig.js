export const systemFields = {
    '队列最大长度': { field: 'maxQueueSize', requestField: 'maxSize', min: 1, max: 10000 },
    '历史记录容量': { field: 'maxHistorySize', requestField: 'historySize', min: 0, max: 10000 },
    '用户点歌上限': { field: 'maxUserSongs', min: 1, max: 10000 },
    '导入单次上限': { field: 'maxPlaylistImportSize', min: 1, max: 10000 },
    '聊天记录容量': { field: 'maxChatHistorySize', min: 0, max: 100000 },
    '发言频率限制(ms)': { field: 'minChatIntervalMs', min: 0, max: 600000 },
    '消息最大长度': { field: 'maxChatMessageLength', min: 1, max: 10000 },
    'B站时长上限(分钟)': { field: 'bilibiliMaxDurationMinutes', min: 1, max: 1440 }
};

export function systemConfigDraft(snapshot) {
    return Object.fromEntries(Object.values(systemFields).map(({ field }) => [field, snapshot[field]]));
}

export function systemConfigUpdate(config) {
    const update = {};
    for (const [label, { field, requestField = field, min, max }] of Object.entries(systemFields)) {
        const value = config[field];
        if (!Number.isInteger(value) || value < min || value > max) {
            throw new RangeError(`${label}应为 ${min} 到 ${max} 之间的整数`);
        }
        update[requestField] = value;
    }
    return update;
}
