import { roomSession } from './roomSession';
import { roomsApi } from '../api/rooms';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useChatStore } from '../stores/chat';
import { useToast } from '../composables/useToast';
import { useAdminStore } from '../stores/admin';
import { adminApi } from '../api/admin';
import { WS_DEST } from '../constants/api';
import { socketService } from './socket';

/**
 * 处理游戏/播放器事件通知 (Toast)
 * 这里集中管理所有的业务通知文案
 */
function handleGameEvent(event) {
    const userStore = useUserStore();
    const chatStore = useChatStore();
    const adminStore = useAdminStore();
    const { show, error } = useToast(); // This now uses the Pinia store wrapper
    const userName = event.userId === 'SYSTEM' ? '系统' : userStore.resolveName(event.userId);

    // 1. 处理特殊业务逻辑 (非 UI 展示)
    if (event.action === 'LIKE') {
        window.dispatchEvent(new CustomEvent('player:like', { detail: { userId: event.userId } }));
    }

    if (event.action === 'ADMIN_TRIGGER') {
        const id = roomSession.roomId;
        if (roomSession.managerToken) roomsApi.manage(id).then(() => {
            if (!userStore.isAuthPassed || roomSession.roomId !== id) return;
            adminStore.isVerified = true; adminStore.showDashboard = true;
        }).catch(() => { if (userStore.isAuthPassed && roomSession.roomId === id) adminStore.showAuthModal = true; });
        else adminStore.showAuthModal = true;
        return;
    }

    if (event.action === 'RESET') {
        chatStore.messages = []; // 清空聊天
    }

    if (event.action === 'PASSWORD_CHANGED') {
        error('房间密码已更改，请重新验证');
        setTimeout(() => {
            userStore.resetAuthentication();
            window.location.reload();
        }, 1500);
        return;
    }

    if (event.action === 'IDLE_KICK') {
        socketService.disconnect();
        userStore.resetAuthentication();
        window.location.reload();
        return;
    }

    if (event.action === 'RENAME_FAILED' || (event.type === 'ERROR' && event.message && (event.message.includes('taken') || event.message.includes('占用')))) {
        // 在改名弹窗内展示失败原因（弹窗背板会模糊背景 toast，故不走 toast）
        userStore.renameError = event.message || '该名称已被占用，请更换';
        userStore.showNameModal = true;
        return;
    }

    // 过滤掉用户进入/离开、以及歌曲开始播放的系统内部通知，避免弹窗干扰
    // 这些事件已经在 Chat Log 中展示，Toast 只展示关键交互
    if (event.action === 'USER_JOIN' || event.action === 'USER_LEAVE' || event.action === 'PLAY_START') {
        return;
    }

    // 2. 使用后端传来的格式化消息
    let msgText = event.message || event.payload || `${userName} ${event.action}`;

    // 将后端 Level 枚举映射为 toast 类型
    const typeMap = { 'error': 'error', 'warn': 'warning', 'success': 'success' };
    let type = typeMap[event.type?.toLowerCase()] || 'info';
    if (event.action === 'ERROR_LOAD') type = 'error';

    show({
        title: event.action === 'ERROR_LOAD' ? 'PLAYBACK ERROR'
             : event.action === 'SYSTEM_MESSAGE' ? 'SYSTEM'
             : event.action === 'MODE_CHANGE' ? '播放模式'
             : event.action,
        message: msgText,
        type: type,
        duration: 3000
    });
}

/**
 * 创建并返回 Socket 订阅配置
 * @returns {Object} 订阅路径 -> 回调函数 的映射
 */
export const createSocketSubscriptions = () => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();
    const chatStore = useChatStore();

    return {
        '/app/user/profile': profile => userStore.syncProfile(profile),
        '/user/queue/profile': profile => userStore.syncProfile(profile),
        '/topic/lifecycle': () => { window.dispatchEvent(new Event('musicparty:return-entry')); },
        '/topic/pairing': () => { window.dispatchEvent(new Event('musicparty:pairing')); },
        // 1. 状态同步
        [WS_DEST.TOPIC_STATE]: (state) => playerStore.syncState(state),
        [WS_DEST.USER_STATE]: (state) => playerStore.syncState(state),

        // 2. 用户列表
        [WS_DEST.TOPIC_USERS]: (users) => userStore.setOnlineUsers(users),

        // 3. 队列更新
        [WS_DEST.TOPIC_QUEUE]: (data) => { playerStore.queue = data; },

        // 4. 事件通知 (Toast)
        [WS_DEST.TOPIC_EVENTS]: handleGameEvent,
        [WS_DEST.USER_EVENTS]: handleGameEvent,

        // 5. 聊天相关
        [WS_DEST.TOPIC_CHAT]: (msg) => chatStore.addMessage(msg),
        [WS_DEST.USER_PRIVATE_CHAT]: (msg) => chatStore.addMessage(msg),

        // 初始历史记录
        [WS_DEST.APP_CHAT_HISTORY]: (history) => chatStore.setHistory(history),

        // 分页历史记录回调
        [WS_DEST.USER_CHAT_HISTORY]: (moreMessages) => chatStore.prependHistory(moreMessages)
    };
};

/**
 * [新增] 创建 Socket 生命周期回调
 * 包含：连接成功处理、断连处理、错误处理
 */
export const createSocketCallbacks = () => {
    const playerStore = usePlayerStore();
    const userStore = useUserStore();

    return {
        // 连接成功
        onConnect: (frame, isCurrent = () => true) => {
            playerStore.connected = true;
            window.dispatchEvent(new Event("musicparty:connected"));
            if (roomSession.ownerAccess) roomsApi.connected(roomSession.roomId).then(result => { if (isCurrent() && result.openAdmin) { const admin=useAdminStore(); admin.isVerified=true; admin.showDashboard=true; } }).catch(() => {});
            // 发起同步
            setTimeout(() => {
                if (isCurrent()) socketService.send(WS_DEST.RESYNC);
            }, 300);
            // Bindings are restored by the shared profile subscription, never overwrite them with a stale tab.
        },

        // 连接断开 (含异常断开)
        onDisconnect: () => {
            playerStore.connected = false;
        },

        // STOMP 协议层错误 (如密码错误、Token失效、服务器内部错误等)
        onStompError: (frame) => {
            console.error('Room connection refused');
            socketService.disconnect();
            window.dispatchEvent(new CustomEvent('musicparty:return-entry', { detail: { message: '房间连接已失效，请重新验证' } }));
        }
    };
};

// 为了兼容旧代码命名，导出这个别名
const handleEventMessage = handleGameEvent;
