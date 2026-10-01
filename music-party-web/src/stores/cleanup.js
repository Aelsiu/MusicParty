import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { roomSession } from '../services/roomSession';
import { adminApi } from '../api/admin';
import { useAdminStore } from './admin';
import { useToast } from '../composables/useToast';

const targets = {
    QUEUE: { id: 'QUEUE', message: '确定要清空本房间的播放队列吗？' },
    OFFLINE: { id: 'OFFLINE', message: '确定要清理本房间中不在线成员的点播歌曲吗？' },
    CHAT: { id: 'CHAT', message: '确定要清空本房间的聊天记录吗？' }
};

export const useCleanupStore = defineStore('cleanup', () => {
    const pending = ref(null), busy = ref(false), error = ref('');
    const admin = useAdminStore();
    const { warning } = useToast();
    function request(id, source = 'command') {
        if (busy.value || !targets[id] || !roomSession.managerToken
                || (source === 'command' && !roomSession.ownerAccess)) return;
        pending.value = { ...targets[id], source, roomId: roomSession.roomId, managerToken: roomSession.managerToken };
        error.value = '';
    }
    function cancel() {
        if (busy.value) return;
        pending.value = null; error.value = '';
    }
    async function confirm() {
        const target = pending.value;
        if (!target || busy.value || target.roomId !== roomSession.roomId
                || target.managerToken !== roomSession.managerToken
                || (target.source === 'command' && !roomSession.ownerAccess)
                || (target.source === 'dashboard' && !admin.showDashboard)) return;
        busy.value = true; error.value = '';
        try {
            const result = await adminApi.clearData('', target.id);
            if (pending.value === target) { pending.value = null; warning(result.message); }
        } catch (failure) {
            if (pending.value === target) error.value = failure.response?.data?.message || '清理操作失败，请重试';
        } finally { busy.value = false; }
    }
    watch(() => [roomSession.roomId, roomSession.managerToken, admin.showDashboard], () => {
        const target = pending.value;
        if (target && (target.roomId !== roomSession.roomId || target.managerToken !== roomSession.managerToken
                || (target.source === 'dashboard' && !admin.showDashboard))) {
            pending.value = null; error.value = '';
        }
    }, { flush: 'sync' });
    return { pending, busy, error, request, cancel, confirm };
});
