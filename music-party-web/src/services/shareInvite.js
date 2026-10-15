import { copyAsyncText } from '../utils/clipboard.js';
import { isPairingCode, normalizePairingCode } from '../utils/pairingCode.js';

export const isShareCommand = text => /^\/\/share(?:\s|$)/i.test(text.trim());

export function roomInviteUrl(origin, roomId, pairingCode) {
    if (!roomId || !isPairingCode(pairingCode)) throw new Error('邀请链接获取失败，请重试');
    let base;
    try {
        base = new URL(origin);
        if (!['https:', 'http:'].includes(base.protocol)) throw new Error('Invalid origin');
    } catch {
        throw new Error('邀请链接生成失败，请刷新页面后重试');
    }
    return `${base.origin}/${encodeURIComponent(roomId)}?pcd=${normalizePairingCode(pairingCode)}`;
}

// Call directly in the click/Enter gesture so promise-backed ClipboardItems
// reserve clipboard access while the server retrieves the current pairing code.
export async function copyRoomInvite({ roomId, origin, invite, isCurrent = () => true,
    copy = copyAsyncText, notify = () => {} }) {
    if (!roomId || !isCurrent()) return false;

    let fetchError;
    const link = Promise.resolve().then(() => {
        if (!isCurrent()) throw new Error('Room changed');
        return invite(roomId);
    }).then(room => {
        if (!isCurrent()) throw new Error('Room changed');
        return roomInviteUrl(origin, roomId, room.pairingCode);
    }).catch(error => { fetchError = error; throw error; });
    link.catch(() => {});

    try {
        await copy(link);
        await link;
        if (!isCurrent()) return false;
        notify('邀请链接已复制');
        return true;
    } catch {
        // Finish the request even if clipboard access was rejected first. API
        // errors have priority and obsolete rooms never receive local feedback.
        await link.catch(() => {});
        if (!isCurrent()) return false;
        notify(fetchError
            ? fetchError.response?.data?.message || fetchError.response?.data?.detail
                || (fetchError instanceof Error ? fetchError.message : '') || '邀请链接获取失败，请重试'
            : '无法访问剪贴板，请允许浏览器复制后重试');
        return false;
    }
}
