import { ref, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { roomSession } from '../services/roomSession';
export function useVideoHistory(player, open) {
    const items = ref([]), total = ref(0), page = ref(0), revision = ref(null), loading = ref(false), error = ref('');
    let sequence = 0, alive = true;
    const load = async (next = 1) => {
        if (!open.value || !player.videoFeatures.history || player.mediaMode !== 'VIDEO') return;
        const context = player.playbackContext(), roomId = roomSession.roomId, request = ++sequence;
        if (!context) return;
        loading.value = true; error.value = '';
        try {
            const result = await videoApi.history(context, next, next === 1 ? undefined : revision.value);
            if (!alive || request !== sequence || !open.value || !matchesVideoRead(result, context, player, roomId)) return;
            items.value = next === 1 ? result.items : [...items.value, ...result.items];
            total.value = result.total; page.value = next; revision.value = result.historyRevision;
        } catch (e) {
            if (!alive || request !== sequence || !player.isContextCurrent(context)) return;
            if (e.response?.data?.code === 'HISTORY_CHANGED' && next !== 1) { load(1); return; }
            error.value = e.response?.data?.message || '历史加载失败，请重试';
        } finally { if (request === sequence) loading.value = false; }
    };
    watch(() => [open.value, player.videoHistoryRevision, player.modeEpoch, player.clientGeneration, player.synchronized], () => {
        sequence++; items.value = []; total.value = 0; page.value = 0; loading.value = false;
        if (open.value) load(1);
    }, { immediate: true });
    onBeforeUnmount(() => { alive = false; sequence++; });
    return { items, total, page, loading, error, load };
}
