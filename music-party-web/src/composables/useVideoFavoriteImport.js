import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { musicApi } from '../api/music';
import { roomSession } from '../services/roomSession';
import { WS_DEST } from '../constants/api';
export const importStatusLabels = { ADDED: '已入队', DUPLICATE: '重复跳过', QUOTA_EXCEEDED: '配额不足', DURATION_EXCEEDED: '时长超限', INVALID_SELECTION: '选择无效', CONTENT_UNAVAILABLE: '内容不可用', CREDENTIALS_REQUIRED: '来源凭据未配置', CREDENTIALS_INVALID: '来源凭据失效', TEMPORARY_FAILURE: '临时来源失败', CANCELLED: '已取消' };
export const terminalImport = status => ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status);
export function useVideoFavoriteImport(player, user) {
    const link = ref(''), preview = ref(null), items = ref([]), selected = ref({}), descriptors = ref({}), busy = ref(false), error = ref(''), currentRequest = ref(null);
    let sequence = 0, alive = true;
    const result = computed(() => currentRequest.value ? player.videoImports[currentRequest.value] : null);
    const uncertain = computed(() => result.value?.kind === 'IMPORT_ERROR' && ['IMPORT_RESULT_UNAVAILABLE', 'TEMPORARY_FAILURE', 'ROOM_BUSY'].includes(result.value?.code || result.value?.error?.code));
    const running = computed(() => !!currentRequest.value && !terminalImport(result.value?.status) && (result.value?.kind !== 'IMPORT_ERROR' || uncertain.value));
    const acknowledgeQueueCheck = () => { if (!uncertain.value) return; currentRequest.value = null; preview.value = null; items.value = []; selected.value = {}; descriptors.value = {}; error.value = '请重新预览，并只选择确认未入队的项目'; };
    const batchLimit = computed(() => Math.min(player.config.maxPlaylistImportSize ?? 100, 100));
    const available = computed(() => Math.max(0, Math.min((player.config.maxQueueSize ?? 1000) - player.queue.length, (player.config.maxUserSongs ?? 100) - player.queue.filter(x => x.enqueuedBy?.token === user.userToken).length)));
    const selection = computed(() => items.value.filter(item => selected.value[item.itemId] !== undefined).map(item => ({ itemId: item.itemId, page: selected.value[item.itemId] })));
    const receivePage = response => { preview.value = { ...preview.value, ...response }; items.value = response.page === 1 ? response.items : [...items.value, ...response.items]; };
    const load = async (next = 1) => {
        const context = player.playbackContext(), room = roomSession.roomId, seq = ++sequence;
        if (!context || !player.videoFeatures.favoriteLinkImport) return;
        busy.value = true; error.value = '';
        try {
            const response = next === 1 ? await videoApi.preview(link.value, context) : await videoApi.previewPage(preview.value.previewId, next, context);
            if (!alive || seq !== sequence || !matchesVideoRead(response, context, player, room)) return;
            if (next === 1) { selected.value = {}; descriptors.value = {}; }
            receivePage(response);
        } catch (e) { if (alive && seq === sequence && player.isContextCurrent(context)) error.value = e.response?.data?.message || '收藏夹预览失败，请重试'; }
        finally { if (seq === sequence) busy.value = false; }
    };
    const toggle = (item, checked) => { const copy = { ...selected.value }; if (checked && item.selectable) copy[item.itemId] = 1; else delete copy[item.itemId]; selected.value = copy; };
    const selectLoaded = () => { for (const item of items.value) if (item.selectable && retryable(item.itemId)) toggle(item, true); };
    const retryable = id => { const prior = result.value?.items?.find(x => x.itemId === id); return !prior || prior.retryable === true; };
    const metadata = async item => {
        const context = player.playbackContext(), seq = sequence;
        try {
            const value = await musicApi.videoMetadata(item.bvid);
            if (alive && seq === sequence && player.isContextCurrent(context)) descriptors.value = { ...descriptors.value, [item.itemId]: value };
        } catch { if (alive && seq === sequence) error.value = '分 P 信息加载失败，请重试'; }
    };
    const submit = () => {
        if (running.value) { player.queryVideoImport({ requestId: currentRequest.value, operationId: result.value?.operationId }); return; }
        if (user.isGuest) { user.showNameModal = true; return; }
        if (!selection.value.length || selection.value.length > batchLimit.value || !preview.value || busy.value) return;
        const context = player.playbackContext(); if (!context) return;
        const snapshot = selection.value.map(item => ({ ...item }));
        if (player.sendMedia(WS_DEST.VIDEO_IMPORT, { previewId: preview.value.previewId, items: snapshot }, context)) { currentRequest.value = context.requestId; player.videoImports = { ...player.videoImports, [context.requestId]: { requestId: context.requestId, roomId: roomSession.roomId, playbackEpoch: context.expectedPlaybackEpoch, modeEpoch: context.expectedModeEpoch, mediaMode: 'VIDEO', status: 'UNKNOWN', operationRevision: -1, protocolVersion: 3 } }; }
    };
    const query = () => currentRequest.value && player.queryVideoImport({ requestId: currentRequest.value, operationId: result.value?.operationId });
    const recent = Object.values(player.videoImports).filter(x => x.roomId === roomSession.roomId && x.playbackEpoch === player.playbackEpoch).at(-1);
    if (recent) { currentRequest.value = recent.requestId; player.queryVideoImport(recent); }
    watch(result, value => {
        if (!terminalImport(value?.status)) return;
        const next = { ...selected.value }; for (const item of value.items || []) if (!item.retryable) delete next[item.itemId]; selected.value = next;
    });
    watch(() => [player.clientGeneration, player.synchronized, player.modeEpoch, roomSession.roomId], () => {
        sequence++; busy.value = false; preview.value = null; items.value = []; selected.value = {}; descriptors.value = {}; error.value = '';
        const latest = Object.values(player.videoImports).filter(x => x.roomId === roomSession.roomId && x.playbackEpoch === player.playbackEpoch).at(-1);
        currentRequest.value = latest?.requestId || null;
        if (player.synchronized && currentRequest.value) query();
    });
    onBeforeUnmount(() => { alive = false; sequence++; });
    return { link, preview, items, selected, descriptors, busy, error, result, running, uncertain, acknowledgeQueueCheck, selection, batchLimit, available, load, toggle, selectLoaded, metadata, submit, query, retryable };
}
