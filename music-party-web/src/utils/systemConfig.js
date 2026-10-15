export const videoResolutionOptions = ['UNLIMITED', '480P', '720P', '1080P', '1440P', '2160P'];
export const systemFields = {
    '队列最大长度': { field: 'maxQueueSize', requestField: 'maxSize', min: 1, max: 10000 },
    '历史记录容量': { field: 'maxHistorySize', requestField: 'historySize', min: 0, max: 10000 },
    '用户点歌上限': { field: 'maxUserSongs', min: 1, max: 10000 },
    '导入单次上限': { field: 'maxPlaylistImportSize', min: 1, max: 10000 },
    '聊天记录容量': { field: 'maxChatHistorySize', min: 0, max: 100000 },
    '发言频率限制(ms)': { field: 'minChatIntervalMs', min: 0, max: 600000 },
    '消息最大长度': { field: 'maxChatMessageLength', min: 1, max: 10000 },
    'B站时长上限(分钟)': { field: 'bilibiliMaxDurationMinutes', min: 1, max: 1440 },
    '视频时长上限(分钟)': { field: 'videoMaxDurationMinutes', min: 1, max: 1440 },
    '配对码更新周期(分钟)': { field: 'pairingIntervalMinutes', min: 1, max: 60 }
};

export function systemConfigDraft(snapshot) {
    return { videoMaxResolution: snapshot.videoMaxResolution ?? 'UNLIMITED', ...Object.fromEntries(Object.values(systemFields).map(({ field }) => [field, snapshot[field] ?? (field === 'videoMaxDurationMinutes' ? 120 : field === 'pairingIntervalMinutes' ? 10 : undefined)])) };
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
    const cap = config.videoMaxResolution ?? 'UNLIMITED';
    if (!videoResolutionOptions.includes(cap)) throw new RangeError('视频分辨率上限无效');
    update.videoMaxResolution = cap;
    return update;
}
