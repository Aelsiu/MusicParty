import { defineStore } from 'pinia';
import { ref, computed, onScopeDispose } from 'vue';
import { roomSession, clearRoom } from '../services/roomSession.js';
import { STORAGE_KEYS } from '../constants/keys.js';
import { BINDING_PROFILES, BINDING_STORAGE_KEYS, readLocalBindings, saveLocalBindings } from '../utils/localBindings.js';

const generateToken = () => {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map(n => n.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
};

let storedToken = localStorage.getItem(STORAGE_KEYS.TOKEN);
if (!storedToken) {
    storedToken = generateToken();
    localStorage.setItem(STORAGE_KEYS.TOKEN, storedToken);
}
const userToken = ref(storedToken);

const storageName = localStorage.getItem(STORAGE_KEYS.USERNAME);
const currentUser = ref({
    name: storageName || '游客',
    sessionId: ''
});

export const useUserStore = defineStore('user', () => {
    const onlineUsers = ref([]);

    const isAuthPassed = ref(false);
    const roomPassword = ref('');
    const roomName = ref('');
    const roomId = computed(() => roomSession.roomId);
    const justReturned = ref(false);

    // 启动时：严格从 LocalStorage 读取，默认值只在这里设定一次
    const storageName = localStorage.getItem('mp_username');
    const currentUser = ref({
        name: storageName || '游客',
        sessionId: ''
    });

    const localBindings = readLocalBindings();
    const bindings = ref(localBindings.bindings);
    const neteaseUsername = ref(localBindings.neteaseUsername);
    const neteaseAvatar = ref(localBindings.neteaseAvatar);
    const bilibiliUsername = ref(localBindings.bilibiliUsername);
    const bilibiliAvatar = ref(localBindings.bilibiliAvatar);
    const bindingFields = { neteaseUsername, neteaseAvatar, bilibiliUsername, bilibiliAvatar };
    const applyBindings = value => {
        // 同账号的头像/昵称补全不能制造一次新的绑定变更，避免重复刷新歌单。
        for (const platform of Object.keys(bindings.value)) if (!Object.hasOwn(value.bindings, platform)) delete bindings.value[platform];
        for (const [platform, id] of Object.entries(value.bindings)) bindings.value[platform] = id;
        for (const [field, target] of Object.entries(bindingFields)) target.value = value[field];
    };
    const browser = globalThis.window;
    const storageChanged = event => {
        if (event.storageArea && event.storageArea !== globalThis.localStorage) return;
        if (event.key === null || BINDING_STORAGE_KEYS.has(event.key)) applyBindings(readLocalBindings());
    };
    browser?.addEventListener?.('storage', storageChanged);
    onScopeDispose(() => browser?.removeEventListener?.('storage', storageChanged));
    // 全局状态：控制改名弹窗显示
    const showNameModal = ref(false);

    // 改名失败原因：在弹窗内展示（弹窗背板会模糊背景 toast，所以不走 toast）
    const renameError = ref('');

    const onNameSetCallback = ref(null);

    const isGuest = ref(!storageName);

    // 核心方法：将 SessionID 翻译成名字
    const resolveName = (id, fallbackName) => {
        if (!id) return 'Unknown';
        if (id === 'ADMIN') return 'AUTO_DJ';

        // 如果 ID 是我自己 (比较 Token)
        if (id === userToken.value) return currentUser.value.name;

        // 否则去在线列表里找 (通过 u.token 匹配)
        const u = onlineUsers.value.find(u => u.token === id);

        return u ? u.name : (fallbackName || 'Unknown Agent');
    };

    /**
     * 2. 初始化用户身份 (来自 /app/user/me)
     * 逻辑：对比服务器认为的名字 (serverName) 和我本地存储的名字
     * serverIsGuest: 后端返回的当前是否为游客状态
     */
    const initUser = (sessionId, serverName, serverIsGuest) => {
        currentUser.value.sessionId = sessionId;
        if (serverIsGuest !== undefined) localStorage.setItem(STORAGE_KEYS.TOKEN, userToken.value);

        // 1. 同步名字
        if (serverName) {
            currentUser.value.name = serverName;
        }

        // 2. 同步身份状态 (以服务端为准)
        if (serverIsGuest !== undefined) {
            // 状态变更检测: Guest -> User (转正)
            if (isGuest.value && !serverIsGuest) {
                console.log("Identity upgraded to User");
                isGuest.value = false;
                localStorage.setItem(STORAGE_KEYS.USERNAME, serverName);
                showNameModal.value = false; // 成功改名后自动关闭弹窗

                // 执行待办回调 (如打开搜索框)
                if (onNameSetCallback.value) {
                    onNameSetCallback.value();
                    onNameSetCallback.value = null;
                }
            }
            // 状态变更检测: User -> Guest (降级/重置)
            else if (!isGuest.value && serverIsGuest) {
                console.log("Identity degraded to Guest");
                isGuest.value = true;
                localStorage.removeItem(STORAGE_KEYS.USERNAME);
            }
        }

        // 3. 如果是正式用户，确保本地存储名字与服务端一致 (处理去重后缀)
        if (!isGuest.value && serverName) {
            localStorage.setItem(STORAGE_KEYS.TOKEN, userToken.value);
            localStorage.setItem(STORAGE_KEYS.USERNAME, serverName);
        }

        return false;
    };

    const setOnlineUsers = (users) => {
        onlineUsers.value = users;
    };

    const updateBinding = (platform, accountId, displayName = '', avatarUrl = '') => {
        const profile = BINDING_PROFILES[platform];
        if (!Object.hasOwn(BINDING_PROFILES, platform) || !['string', 'number'].includes(typeof accountId)) return false;
        const id = String(accountId).trim();
        // 合并其他标签页刚写入的平台，避免事件尚未到达时覆盖它。
        const next = readLocalBindings();
        const sameAccount = next.bindings[platform] === id;
        if (id) next.bindings[platform] = id;
        else delete next.bindings[platform];
        next[profile.name] = id ? String(displayName || (sameAccount && next[profile.name]) || id) : '';
        next[profile.avatar] = id ? String(avatarUrl || (sameAccount && next[profile.avatar]) || '') : '';
        if (!saveLocalBindings(next)) return false;
        applyBindings(next);
        return true;
    };

    // 废弃: 不再直接修改本地状态，改为等待 initUser 的后端回调
    const saveName = (newName) => {
        // Logic moved to initUser response handling
    }

    const setPostNameAction = (fn) => {
        onNameSetCallback.value = fn;
    }

    const resetAuthentication = (navigate = true) => {
        isAuthPassed.value = false;
        justReturned.value = true;
        clearRoom(navigate);
        roomName.value = '';
        currentUser.value.sessionId = '';
        showNameModal.value = false;
        renameError.value = '';
        onNameSetCallback.value = null;
        roomPassword.value = '';
        localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD); // 清理旧版本保存的房间密码
    };

    const prepareEntry = (name, password) => {
        justReturned.value = false;
        const previousName = localStorage.getItem(STORAGE_KEYS.USERNAME);
        // A name edited on CONNECT is provisional until the server confirms it.
        // Returning to the remembered name must use its persisted profile token.
        if (previousName && name === previousName) {
            userToken.value = localStorage.getItem(STORAGE_KEYS.TOKEN) || userToken.value;
        }
        if ((previousName && name !== previousName) || (!previousName && name !== currentUser.value.name)) {
            // 入房时换 ID 创建新身份，等服务器确认后再保存，避免在 CONNECT 前刷新丢失旧身份。
            userToken.value = generateToken();
            currentUser.value.sessionId = '';
        }
        currentUser.value.name = name;
        roomPassword.value = password;
    };
    const syncProfile = (profile) => {
        if (profile.name) { currentUser.value.name = profile.name; if (!isGuest.value) localStorage.setItem(STORAGE_KEYS.USERNAME, profile.name); }
        // 兼容旧服务端的资料消息，但绑定只以本机记录为准。
    };

    return {
        roomId, justReturned, syncProfile,
        onlineUsers,
        currentUser,
        bindings,
        neteaseUsername,
        neteaseAvatar,
        bilibiliUsername,
        bilibiliAvatar,
        initUser,
        setOnlineUsers,
        updateBinding,
        saveName,
        isGuest,
        showNameModal,
        renameError,
        resolveName,
        userToken,
        setPostNameAction,
        isAuthPassed,
        roomPassword,
        roomName,
        prepareEntry,
        resetAuthentication
    };
});
