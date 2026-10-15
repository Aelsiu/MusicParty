import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { musicApi } from '../api/music';
import { roomSession } from '../services/roomSession';
import { WS_DEST } from '../constants/api';
export const importStatusLabels = { XIFAN_UNAVAILABLE:"XIFAN暂不可用",MP4_NOT_SUPPORTED:"不支持的MP4",MEDIA_METADATA_UNAVAILABLE:"时长确认失败",MEDIA_CONTENT_CHANGED:"媒体已变化",MEDIA_ROUTE_UNSUPPORTED:"线路未支持",TIMEOUT:"超时", ADDED: '已入队', DUPLICATE: '重复跳过', QUOTA_EXCEEDED: '配额不足', DURATION_EXCEEDED: '时长超限', INVALID_SELECTION: '选择无效', CONTENT_UNAVAILABLE: '内容不可用', CREDENTIALS_REQUIRED: '来源凭据未配置', CREDENTIALS_INVALID: '来源凭据失效', TEMPORARY_FAILURE: '临时来源失败', CANCELLED: '已取消' };
export const terminalImport = status => ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status);
export function useVideoFavoriteImport(player, user, type = 'FAVORITES', feedback = null) {
    const link = ref(''), preview = ref(null), items = ref([]), selected = ref({}), descriptors = ref({}), busy = ref(false), error = ref(''), currentRequest = ref(null);
    let sequence = 0, alive = true, confirmationTimers = [], confirmationGeneration = 0;
    let feedbackTicket;
    let importContext;
    const settledItems = ref({});
    const uncertainRequest = ref(null), submittedPreviewId = ref(null);
    const cancelConfirmation = () => { confirmationGeneration++; confirmationTimers.forEach(clearTimeout); confirmationTimers = []; };
    const confirmation = computed(() => uncertain.value ? '结果暂无法确认，请先核对待播队列' : running.value ? '正在确认结果' : '');
    const result = computed(() => currentRequest.value ? player.videoImports[currentRequest.value] : null);
    const uncertain = computed(() => uncertainRequest.value === currentRequest.value && !!currentRequest.value || result.value?.kind === 'IMPORT_ERROR' && ['IMPORT_RESULT_UNAVAILABLE', 'TEMPORARY_FAILURE', 'ROOM_BUSY'].includes(result.value?.code || result.value?.error?.code));
    const running = computed(() => !!currentRequest.value && !terminalImport(result.value?.status) && (result.value?.kind !== 'IMPORT_ERROR' || uncertain.value));
    const acknowledgeQueueCheck = () => { if (!uncertain.value) return; cancelConfirmation(); uncertainRequest.value = null; currentRequest.value = null; preview.value = null; items.value = []; selected.value = {}; descriptors.value = {}; error.value = '请重新预览，并只选择确认未入队的项目'; };
    const batchLimit = computed(() => Math.min(['PGC_EPISODES','XIFAN_EPISODES'].includes(type) ? preview.value?.batchLimit ?? 0 : player.config.maxPlaylistImportSize ?? 100, 100));
    const available = computed(() => Math.max(0, Math.min((player.config.maxQueueSize ?? 1000) - player.queue.length, (player.config.maxUserSongs ?? 100) - player.queue.filter(x => x.enqueuedBy?.token === user.userToken).length)));
    const selection = computed(() => items.value.filter(item => selected.value[item.itemId] !== undefined).map(item => ['PGC_EPISODES','XIFAN_EPISODES'].includes(type) ? {itemId:item.itemId} : ({ itemId: item.itemId, page: selected.value[item.itemId] })));
    const receivePage = response => { preview.value = { ...preview.value, ...response }; items.value = response.page === 1 ? response.items : [...items.value, ...response.items]; };
    const acceptPreview = response => { if (preview.value?.previewId !== response.previewId) settledItems.value = {}; sequence++; busy.value = false; error.value = ''; if (!running.value) currentRequest.value = null; selected.value = {}; descriptors.value = {}; receivePage(response); };
    const clearRead = () => { sequence++; busy.value = false; preview.value = null; items.value = []; selected.value = {}; descriptors.value = {}; error.value = ''; };
    const load = async (next = 1) => {
        const context = player.playbackContext(), room = roomSession.roomId, seq = ++sequence;
        if (!context || !player.videoFeatures.favoriteLinkImport) { feedback?.fail('load',!context?'房间连接尚未就绪':'当前服务尚未启用收藏夹预览');return; }
        busy.value = true; error.value = '';
        const ticket = feedback?.begin('load');
        try {
            const response = next === 1 ? await videoApi.preview(link.value, context) : await videoApi.previewPage(preview.value.previewId, next, context);
            if (!alive || seq !== sequence) return;
            if (!matchesVideoRead(response, context, player, room)) { error.value='读取响应已过期，请重试';return; }
            if (next === 1) { selected.value = {}; descriptors.value = {}; }
            receivePage(response);
        } catch (e) { if (alive && seq === sequence && player.isContextCurrent(context)) error.value = e.response?.data?.message || '收藏夹预览失败，请重试'; }
        finally { if (seq === sequence) { busy.value = false; feedback?.finish(ticket, !error.value, error.value || (preview.value?.truncated?'本次仅返回前 500 项候选':'')); } }
    };
    const toggle = (item, checked) => { const copy = { ...selected.value }; if (checked && item.selectable) copy[item.itemId] = 1; else delete copy[item.itemId]; selected.value = copy; };
    const selectLoaded = () => { for (const item of items.value) if (item.selectable && retryable(item.itemId)) toggle(item, true); };
    const retryable = id => { const prior = settledItems.value[id] || result.value?.items?.find(x => x.itemId === id); return !prior || prior.retryable === true; };
    const metadata = async item => {
        const context = player.playbackContext(), seq = sequence;
        const ticket = feedback?.begin('load');
        try {
            const value = await musicApi.videoMetadata(item.bvid);
            if (alive && seq === sequence && player.isContextCurrent(context)) { descriptors.value = { ...descriptors.value, [item.itemId]: value }; feedback?.finish(ticket,true); }
        } catch { if (alive && seq === sequence) { error.value = '分 P 信息加载失败，请重试'; feedback?.finish(ticket,false,error.value); } }
    };
    const submit = () => {
        if (running.value) { feedbackTicket=feedback?.begin('queue');player.queryVideoImport({ requestId: currentRequest.value, operationId: result.value?.operationId });confirmOriginal();return; }
        if (user.isGuest) { user.showNameModal = true; feedback?.fail('queue','请先设置昵称'); return; }
        if (!selection.value.length || selection.value.length > batchLimit.value || !preview.value || busy.value) return;
        const context = player.playbackContext(); if (!context) { feedback?.fail('queue','房间连接尚未就绪');return; }
        const snapshot = selection.value.map(item => ({ ...item }));
        if(type==='XIFAN_EPISODES'&&!player.videoFeatures.xifanEpisodeImport)return;
        if (type === 'PGC_EPISODES' && !player.videoFeatures.pgcEpisodeImport) return;
        if (player.sendMedia(type === 'XIFAN_EPISODES' ? '/app/video/import/xifan' : type === 'PGC_EPISODES' ? WS_DEST.VIDEO_EPISODES_IMPORT : type === 'VIDEO_PARTS' ? WS_DEST.VIDEO_PARTS_IMPORT : WS_DEST.VIDEO_IMPORT, { previewId: preview.value.previewId, items: snapshot }, context)) { submittedPreviewId.value = preview.value.previewId; currentRequest.value = context.requestId; player.videoImports = { ...player.videoImports, [context.requestId]: { importType: type, requestId: context.requestId, roomId: roomSession.roomId, playbackEpoch: context.expectedPlaybackEpoch, modeEpoch: context.expectedModeEpoch, mediaMode: 'VIDEO', status: 'UNKNOWN', operationRevision: -1, protocolVersion: 3 } }; uncertainRequest.value = null; confirmOriginal(context); }
        else feedback?.fail('queue','无法发送入队请求，请确认房间连接');
    };
    function confirmOriginal(capturedContext) {
        if(capturedContext)importContext=capturedContext;
        cancelConfirmation(); const token = confirmationGeneration, id = currentRequest.value, context = capturedContext || importContext || player.playbackContext();
        const room = roomSession.roomId, roomGeneration = roomSession.generation;
        const valid = () => alive && token === confirmationGeneration && id === currentRequest.value && room === roomSession.roomId
            && roomGeneration === roomSession.generation && player.isContextCurrent(context) && !terminalImport(result.value?.status);
        const xifan=type==='XIFAN_EPISODES';
        for (const delay of xifan?[1000,3000,10000,25000,45000]:[1000,3000]) confirmationTimers.push(setTimeout(() => { if (valid()) query(); },delay));
        confirmationTimers.push(setTimeout(() => { if (valid()) { uncertainRequest.value = id;feedback?.finish(feedbackTicket,false,'结果暂无法确认，请核对待播队列；再次操作只查询原请求。'); } },xifan?55000:10000));
    }
    const query = () => currentRequest.value && player.queryVideoImport({ requestId: currentRequest.value, operationId: result.value?.operationId });
    const recent = Object.values(player.videoImports).filter(x => (x.importType || 'FAVORITES') === type && x.roomId === roomSession.roomId && x.playbackEpoch === player.playbackEpoch).at(-1);
    if (recent) { currentRequest.value = recent.requestId; player.queryVideoImport(recent); }
    watch(result, value => {
        if (!terminalImport(value?.status)) return;
        cancelConfirmation(); uncertainRequest.value = null;
        if (preview.value?.previewId === submittedPreviewId.value) {
            const settled = { ...settledItems.value };
            for (const item of value.items || []) if (!item.retryable) settled[item.itemId] = item;
            settledItems.value = settled;
        }
        const next = { ...selected.value }; for (const item of value.items || []) if (!item.retryable) delete next[item.itemId]; selected.value = next;
    });
    watch(() => [player.clientGeneration, player.synchronized, player.modeEpoch, roomSession.roomId], () => {
        importContext=null;
        cancelConfirmation(); uncertainRequest.value = null; sequence++; busy.value = false; preview.value = null; items.value = []; selected.value = {}; descriptors.value = {}; error.value = '';
        const latest = Object.values(player.videoImports).filter(x => (x.importType || 'FAVORITES') === type && x.roomId === roomSession.roomId && x.playbackEpoch === player.playbackEpoch).at(-1);
        currentRequest.value = latest?.requestId || null;
        if (player.synchronized && currentRequest.value) { query(); confirmOriginal(); }
    });
    watch(currentRequest, value => { if (value) feedbackTicket = feedback?.begin('queue'); }, {flush:'sync'});
    watch(()=>selection.value.length, value=>{if(value>batchLimit.value)feedback?.fail('queue',`本批上限 ${batchLimit.value} 项，请减少选择`);});
    watch([result, uncertain], ([value, unknown]) => {
        if (unknown) { feedback?.finish(feedbackTicket,false,'结果暂无法确认，请核对待播队列；再次操作只查询原请求。'); return; }
        if (value?.kind === 'IMPORT_ERROR') { feedback?.finish(feedbackTicket,false,value.message || '入队请求失败'); return; }
        if (!terminalImport(value?.status)) return;
        const success=value.status==='COMPLETED' && !!value.items?.length && value.items.every(item=>item.status==='ADDED');
        const failures=(value.items||[]).filter(item=>item.status!=='ADDED').map(item=>`${item.itemId}: ${importStatusLabels[item.status]||'请求失败'}`).join('；');
        feedback?.finish(feedbackTicket,success,failures);
    }, {flush:'sync'});
    watch(error, message => { if (message) feedback?.fail(running.value?'queue':'load',message); });
    onBeforeUnmount(() => { alive = false; sequence++; cancelConfirmation(); });
    return { link, preview, items, selected, descriptors, busy, error, result, running, uncertain, confirmation, submittedPreviewId, acknowledgeQueueCheck, selection, batchLimit, available, load, toggle, selectLoaded, metadata, submit, query, retryable, acceptPreview, clearRead };
}
