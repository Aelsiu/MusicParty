import client from './client';
import { roomsApi } from './rooms';
import { roomSession, saveManager } from '../services/roomSession';
import { socketService } from '../services/socket';
import { useUserStore } from '../stores/user';
import { verifyRoomOwner } from '../services/adminVerification';

/**
 * 管理员后台专用接口封装
 */
export const adminApi = {
    // 验证当前房间的许可，并升级现有连接；失败时保留原管理会话。
    verify: async (password) => {
        const user = useUserStore();
        const connection = { roomId: roomSession.roomId, roomToken: roomSession.roomToken,
            sessionId: user.currentUser.sessionId, generation: socketService.generation,
            managerToken: roomSession.managerToken };
        return verifyRoomOwner(password, {
            connection,
            login: roomsApi.login,
            promote: (current, token) => client.post(`/api/rooms/${current.roomId}/owner`,
                { sessionId: current.sessionId }, { skipManagementAuth: true, headers: {
                    Authorization: `Bearer ${token}`, 'X-Room-Token': current.roomToken,
                    'X-Room-ID': current.roomId
                } }),
            isCurrent: () => user.isAuthPassed && socketService.connected
                && socketService.generation === connection.generation
                && user.currentUser.sessionId === connection.sessionId
                && roomSession.roomId === connection.roomId
                && roomSession.roomToken === connection.roomToken
                && roomSession.managerToken === connection.managerToken,
            commit: session => { saveManager(session); roomSession.ownerAccess = true; }
        });
    },

    // 锁定控制 (PAUSE/SKIP/SHUFFLE/ALL)
    setLock: (adminPwd, type, locked) => client.post('/api/admin/lock', { type, locked }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 强制播放器操作 (PAUSE/SKIP/SHUFFLE)
    playerAction: (adminPwd, action) => client.post('/api/admin/player/action', { action }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 清理数据 (QUEUE/CHAT)
    clearData: (adminPwd, target) => client.post('/api/admin/room/clear', { target }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 系统重置
    resetSystem: (adminPwd) => client.post('/api/admin/system/reset', {}, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 更新平台 Cookie
    setCookie: (adminPwd, platform, value) => client.post('/api/admin/config/cookie', { platform, value }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 直播流控制
    setStream: (adminPwd, enabled) => client.post('/api/admin/room/stream', { enabled }, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 更新系统配置
    updateConfig: (adminPwd, config) => client.post('/api/admin/config/update', config, {
        headers: { 'X-Admin-Password': adminPwd }
    }),

    // 更新私人电台/私人DJ 配置
    updatePrivateDj: (adminPwd, update) => client.post('/api/admin/private-dj', update, {
        headers: { 'X-Admin-Password': adminPwd }
    })
};
