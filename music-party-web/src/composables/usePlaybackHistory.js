import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { musicApi } from '../api/music';
import { roomSession } from '../services/roomSession';
export function usePlaybackHistory(player, open, fixedMode) {
    const mode = () => fixedMode || player.mediaMode;
    const capable = () => mode() === 'VIDEO' ? player.videoFeatures.history : player.queueFeatures?.musicHistory;
    const historyRevision = () => mode() === 'VIDEO' ? player.videoHistoryRevision : player.musicHistoryRevision;
    const items = ref([]), total = ref(0), page = ref(0), revision = ref(null), error = ref('');
    const status = ref('INITIAL'), requestedPage = ref(0), refreshing = ref(false);
    let hasSuccess = false, retryRefresh = false;
    const loading = computed(() => status.value === 'LOADING' || refreshing.value);
    const appending = computed(() => loading.value && requestedPage.value > 1);
    const available = () => open.value && capable() && player.mediaMode === mode()
        && player.synchronized && !!roomSession.roomId;
    const isEmpty = computed(() => available() && (status.value === 'SUCCESS' || refreshing.value && hasSuccess) && items.value.length === 0 && total.value === 0);
    const contextState = () => [open.value, roomSession.generation, roomSession.roomId, capable(),
        player.mediaMode, player.playbackEpoch, player.modeEpoch, player.clientGeneration, player.synchronized];
    let sequence = 0, alive = true;
    const performLoad = async (next = 1, update = false, restart = 0) => {
        if (!alive || !available()) return false;
        const context = player.playbackContext(), roomId = roomSession.roomId, request = ++sequence;
        if (!context) { status.value = 'INITIAL'; return false; }
        const captured = contextState();
        const isCurrent = () => {
            const current = contextState();
            return alive && request === sequence && available() && player.isContextCurrent(context)
                && captured.every((value, index) => value === current[index]);
        };
        const loadedPages = Math.max(1, page.value);
        refreshing.value = update && hasSuccess; retryRefresh = update;
        status.value = 'LOADING'; requestedPage.value = next; error.value = '';
        const read = async (number, expectedRevision) => {
            const result = await (mode() === 'VIDEO' ? videoApi : musicApi).history(context, number, expectedRevision);
            if (!isCurrent()) return null;
            if (!matchesVideoRead(result, context, player, roomId) || !Array.isArray(result.items)
                || !Number.isInteger(result.total) || result.total < 0 || result.page !== number
                || !Number.isInteger(result.historyRevision) || result.historyRevision < 0) throw new Error('Invalid history response');
            if (expectedRevision !== undefined && result.historyRevision !== expectedRevision) throw { response: { data: { code: 'HISTORY_CHANGED' } } };
            return result;
        };
        try {
            const result = await read(next, next === 1 ? undefined : revision.value);
            if (!result) return false;
            let rows = next === 1 ? [...result.items] : [...items.value, ...result.items], last = next;
            if (update) {
                const pages = Math.min(loadedPages, Math.max(1, Math.ceil(result.total / 20)));
                for (let number = 2; number <= pages; number++) {
                    const additional = await read(number, result.historyRevision); if (!additional) return false;
                    if (additional.total !== result.total) throw new Error('Inconsistent history total');
                    rows.push(...additional.items); last = number;
                }
            }
            items.value = rows;
            total.value = result.total; page.value = last; revision.value = result.historyRevision;
            status.value = 'SUCCESS'; hasSuccess = true; refreshing.value = false;
            return true;
        } catch (e) {
            if (!isCurrent()) return false;
            // A changed revision restarts page one, retaining visible rows until
            // the fresh first page succeeds. The failed page is never appended.
            if (e.response?.data?.code === 'HISTORY_CHANGED' && restart < 1) return performLoad(1, true, restart + 1);
            error.value = e.response?.data?.message || '历史加载失败，请重试';
            status.value = 'ERROR'; refreshing.value = false;
            return false;
        }
    };
    const load = (next = 1) => loading.value || !Number.isInteger(next) || next < 1
        ? Promise.resolve(false) : performLoad(next);
    const retry = () => loading.value ? Promise.resolve(false) : performLoad(requestedPage.value || 1, retryRefresh);
    const refresh = (clearItems) => {
        const scheduled = ++sequence;
        error.value = ''; requestedPage.value = 1;
        if (clearItems) { items.value = []; total.value = 0; page.value = 0; revision.value = null; hasSuccess = false; }
        refreshing.value = !clearItems && hasSuccess && available();
        status.value = available() ? 'LOADING' : 'INITIAL';
        // Room selection updates several fields together. Invalidate immediately,
        // then fetch once after those synchronous changes have settled.
        Promise.resolve().then(() => { if (alive && scheduled === sequence && available()) performLoad(1, !clearItems); });
    };
    watch(contextState, (current, previous) => {
        if (previous && current.every((value, index) => Object.is(value, previous[index]))) return;
        refresh(true);
    }, { immediate: true, flush: 'sync' });
    watch(historyRevision, () => refresh(false), { flush: 'sync' });
    onBeforeUnmount(() => { alive = false; sequence++; });
    return { items, total, page, status, isEmpty, loading, appending, refreshing, error, load, retry };
}
