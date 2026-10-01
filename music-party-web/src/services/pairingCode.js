import { copyAsyncText } from '../utils/clipboard.js';

export const isPairingCodeCommand = text => /^\/\/code(?:\s|$)/i.test(text.trim());

export async function copyRoomPairingCode({ roomId, managerToken, manage, notify,
    isCurrent = () => true, copy = copyAsyncText }) {
    if (!managerToken) {
        notify('请先使用 //admin 验证本房间所属许可或最高许可');
        return false;
    }

    let fetchError;
    const code = Promise.resolve().then(() => manage(roomId)).then(room => {
        if (!isCurrent()) throw new Error('Room changed');
        if (!/^\d{4}$/.test(room.pairingCode)) throw new Error('配对码获取失败，请重试');
        return room.pairingCode;
    }).catch(error => { fetchError = error; throw error; });

    try {
        await copy(code);
        if (!isCurrent()) return false;
        notify('配对码已复制');
        return true;
    } catch {
        // Wait for the authorized request so API errors take priority over a
        // simultaneous clipboard rejection, and no promise is left unhandled.
        await code.catch(() => {});
        if (!isCurrent()) return false;
        notify(fetchError
            ? fetchError.response?.data?.message || fetchError.response?.data?.detail || '配对码获取失败，请确认管理许可有效且拥有本房间权限'
            : '无法访问剪贴板，请在房间管理面板中复制配对码');
        return false;
    }
}
