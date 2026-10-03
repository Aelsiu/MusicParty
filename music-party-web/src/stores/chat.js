import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { useUserStore } from './user';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';
import { roomSession, syncRoomShare } from '../services/roomSession';
import { roomsApi } from '../api/rooms';
import { copyRoomPairingCode } from '../services/pairingCode.js';
import { copyRoomInvite } from '../services/shareInvite.js';

export const useChatStore = defineStore('chat', () => {
    // 状态
    const messages = ref([]);
    const unreadCount = ref(0);
    const isOpen = ref(false);

    // 分页相关状态
    const hasMore = ref(true);
    const isLoadingMore = ref(false);

    const userStore = useUserStore();
    const LIMIT_PER_PAGE = 50;

    // 1. 添加单条消息 (来自 WebSocket 推送)
    const addMessage = (msg) => {
        messages.value.push(msg);

        if (messages.value.length > 2000) {
            messages.value = messages.value.slice(-1000);
        }

        // 未读计数逻辑：窗口关闭 && 不是自己发的 && 是普通聊天消息
        const isSelf = msg.userId === userStore.userToken;
        if (!isOpen.value && !isSelf && msg.type === 'CHAT') {
            unreadCount.value++;
        }
    };

    // 2. 初始化历史记录 (连接成功后获取最近 50 条)
    const setHistory = (history) => {
        messages.value = history; // 覆盖
        unreadCount.value = 0;

        // 如果返回数量少于分页限制，说明没有更多了
        hasMore.value = history.length >= LIMIT_PER_PAGE;
    };

    // 3. 加载更多历史记录 (向上滚动触发)
    const loadMoreHistory = () => {
        if (!hasMore.value || isLoadingMore.value) return;

        isLoadingMore.value = true;
        const currentCount = messages.value.filter(msg => !msg.private).length;

        // 发送 WebSocket 请求
        socketService.send(WS_DEST.CHAT_HISTORY_FETCH, {
            offset: currentCount,
            limit: LIMIT_PER_PAGE
        });
    };

    // 4. 处理加载到的更多历史数据 (回调)
    const prependHistory = (moreMessages) => {
        if (moreMessages.length === 0) {
            hasMore.value = false;
        } else {
            // 将旧消息拼接到数组头部
            messages.value = [...moreMessages, ...messages.value];
            if (moreMessages.length < LIMIT_PER_PAGE) {
                hasMore.value = false;
            }
        }
        isLoadingMore.value = false;
    };

    const toggleChat = () => {
        isOpen.value = !isOpen.value;
        if (isOpen.value) {
            unreadCount.value = 0;
        }
    };

    let copyingPairingCode = false;
    const copyPairingCode = async () => {
        if (copyingPairingCode || !userStore.isAuthPassed || userStore.isGuest) return;
        if (!roomSession.ownerAccess) {
            addMessage({ id: `private-${Date.now()}-${Math.random()}`, userId: 'SYSTEM', userName: 'SYSTEM',
                content: '此指令仅限本房间 Owner 或 Root 使用，请通过管理入口进入房间',
                timestamp: Date.now(), type: 'SYSTEM', private: true });
            return;
        }
        const { roomId, managerToken } = roomSession;
        const isCurrent = () => userStore.isAuthPassed && roomSession.roomId === roomId
            && roomSession.managerToken === managerToken;
        copyingPairingCode = true;
        try {
            await copyRoomPairingCode({
                roomId, managerToken, manage: roomsApi.manage, isCurrent,
                // Local-only feedback: never send it to the room or its history.
                notify: content => addMessage({
                    id: `private-${Date.now()}-${Math.random()}`, userId: 'SYSTEM', userName: 'SYSTEM',
                    content, timestamp: Date.now(), type: 'SYSTEM', private: true
                })
            });
        } finally {
            copyingPairingCode = false;
        }
    };

    const privateNotice = content => addMessage({
        id: `private-${Date.now()}-${Math.random()}`, userId: 'SYSTEM', userName: 'SYSTEM',
        content, timestamp: Date.now(), type: 'SYSTEM', private: true
    });
    let sharingInvite = null, updatingShare = null;
    watch(() => roomSession.generation, () => { sharingInvite = null; updatingShare = null; }, { flush: 'sync' });

    const shareContext = () => {
        const { roomId, roomToken, managerToken, ownerAccess, generation } = roomSession;
        return {
            roomId, generation,
            isCurrent: () => userStore.isAuthPassed && !userStore.isGuest
                && roomSession.roomId === roomId && roomSession.generation === generation
                && roomSession.roomToken === roomToken && roomSession.ownerAccess === ownerAccess
                && (!ownerAccess || roomSession.managerToken === managerToken)
        };
    };

    const runShareCommand = async (parameter = '', valid = true) => {
        if (!userStore.isAuthPassed || userStore.isGuest) return false;
        if (!valid || !['', 'on', 'off'].includes(parameter)) {
            privateNotice('用法：//share、//share on、//share off');
            return false;
        }
        if (parameter && !roomSession.ownerAccess) {
            privateNotice('此指令仅限本房间 Owner 或 Root 使用，请通过管理入口进入房间');
            return false;
        }
        const context = shareContext();
        if (!context.roomId) return false;

        if (!parameter) {
            if (sharingInvite) return false;
            if (!roomSession.shareEnabled) {
                privateNotice('房间邀请分享已关闭');
                return false;
            }
            const operation = {};
            const revision = roomSession.shareRevision;
            sharingInvite = operation;
            const isCurrent = () => sharingInvite === operation && context.isCurrent()
                && roomSession.shareEnabled && roomSession.shareRevision === revision;
            try {
                const copied = await copyRoomInvite({
                    roomId: context.roomId, origin: window.location.origin, invite: roomsApi.invite,
                    isCurrent,
                    notify: privateNotice
                });
                if (!copied || !isCurrent()) return false;
                window.dispatchEvent(new CustomEvent('musicparty:invite-copied', { detail: {
                    roomId: context.roomId, generation: context.generation, shareRevision: revision
                } }));
                return true;
            } finally {
                if (sharingInvite === operation) sharingInvite = null;
            }
        }

        if (updatingShare) return false;
        const operation = {};
        const revision = roomSession.shareRevision;
        updatingShare = operation;
        try {
            const room = await roomsApi.setShareEnabled(context.roomId, parameter === 'on');
            if (updatingShare !== operation || !context.isCurrent()) return false;
            if (!syncRoomShare(room.shareEnabled, context.roomId, context.generation, revision)) {
                privateNotice('房间分享状态已更新，请以当前状态为准');
                return false;
            }
            privateNotice(room.shareEnabled ? '已允许所有房间成员分享邀请链接' : '已关闭所有房间成员的邀请分享');
            return true;
        } catch (error) {
            if (updatingShare === operation && context.isCurrent()) {
                privateNotice(error.response?.data?.message || error.response?.data?.detail || '分享状态更新失败，请重试');
            }
            return false;
        } finally {
            if (updatingShare === operation) updatingShare = null;
        }
    };
    const shareInvite = () => runShareCommand();

    return {
        messages,
        unreadCount,
        isOpen,
        hasMore,
        isLoadingMore,
        addMessage,
        toggleChat,
        setHistory,
        loadMoreHistory,
        prependHistory,
        copyPairingCode,
        shareInvite,
        runShareCommand
    };
});
