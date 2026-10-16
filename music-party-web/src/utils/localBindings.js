import { STORAGE_KEYS } from '../constants/keys.js';

export const BINDING_PROFILES = {
    netease: { name: 'neteaseUsername', avatar: 'neteaseAvatar', nameKey: STORAGE_KEYS.NETEASE_USERNAME, avatarKey: STORAGE_KEYS.NETEASE_AVATAR },
    bilibili: { name: 'bilibiliUsername', avatar: 'bilibiliAvatar', nameKey: STORAGE_KEYS.BILIBILI_USERNAME, avatarKey: STORAGE_KEYS.BILIBILI_AVATAR }
};
export const BINDING_STORAGE_KEYS = new Set([STORAGE_KEYS.BINDINGS,
    ...Object.values(BINDING_PROFILES).flatMap(profile => [profile.nameKey, profile.avatarKey])]);

export function readLocalBindings(storage = globalThis.localStorage) {
    const result = { bindings: {}, neteaseUsername: '', neteaseAvatar: '', bilibiliUsername: '', bilibiliAvatar: '' };
    try {
        const raw = JSON.parse(storage.getItem(STORAGE_KEYS.BINDINGS) || '{}');
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return result;
        for (const [platform, profile] of Object.entries(BINDING_PROFILES)) {
            const id = raw[platform];
            if ((typeof id !== 'string' && typeof id !== 'number') || !String(id).trim()) continue;
            result.bindings[platform] = String(id).trim();
            result[profile.name] = storage.getItem(profile.nameKey) || result.bindings[platform];
            result[profile.avatar] = storage.getItem(profile.avatarKey) || '';
        }
    } catch { /* 无法读取或损坏的本机数据不恢复为服务端绑定。 */ }
    return result;
}

export function saveLocalBindings(value, storage = globalThis.localStorage) {
    try {
        for (const profile of Object.values(BINDING_PROFILES)) {
            for (const [field, key] of [[profile.name, profile.nameKey], [profile.avatar, profile.avatarKey]]) {
                if (value[field]) storage.setItem(key, value[field]);
                else storage.removeItem(key);
            }
        }
        // 解绑也写入空集合；空记录是用户的选择，不能从旧服务端资料补回。
        storage.setItem(STORAGE_KEYS.BINDINGS, JSON.stringify(value.bindings));
        return true;
    } catch { return false; }
}
