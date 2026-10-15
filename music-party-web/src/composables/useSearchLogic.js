import { ref, watch, onScopeDispose } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';
import { usePlayerStore } from '../stores/player';
import { roomSession } from '../services/roomSession';

export function useSearchLogic(emit, feedback = null) {
    const { error } = useToast();

    const player = usePlayerStore();
    const platform = ref(player.mediaMode === 'VIDEO' ? 'bilibili' : 'netease');
    let requestSequence = 0, alive = true;
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'
    const invalidateSearch = () => { requestSequence++; loading.value = false; songs.value = []; };
    watch(keyword, () => { requestSequence++; loading.value = false; }, { flush: 'sync' });
    watch(() => [player.mediaMode, player.modeEpoch, player.clientGeneration], () => {
        requestSequence++; songs.value = []; keyword.value = ''; loading.value = false; listMode.value = 'search';
        if (player.mediaMode === 'VIDEO') platform.value = 'bilibili';
    }, { flush: 'sync' });
    onScopeDispose(() => { alive = false; requestSequence++; });

    const doSearch = async (query) => {
        const val = typeof query === 'string' ? query.trim() : keyword.value.trim();
        if (!val) return;
        const request = ++requestSequence, sourcePlatform = platform.value;
        const generation = player.clientGeneration, modeEpoch = player.modeEpoch;
        const roomGeneration = roomSession.generation, input = keyword.value;
        const isCurrent = () => alive && request === requestSequence && platform.value === sourcePlatform
            && keyword.value === input && roomSession.generation === roomGeneration && player.clientGeneration === generation && player.modeEpoch === modeEpoch;

        // 普通搜索
        listMode.value = 'search';
        loading.value = true;
        songs.value = [];
        const ticket=feedback?.begin('search');
        try {
            const data = await musicApi.search(sourcePlatform, val);
            if (isCurrent()) { songs.value = data; feedback?.finish(ticket,true); }
        } catch (e) {
            if (!isCurrent()) return;
            const msg = e.response?.data?.message || 'Search Failed';
            if (feedback) feedback.finish(ticket,false,msg); else error(msg);
        } finally {
            if (isCurrent()) loading.value = false;
        }
    };

    return {
        platform,
        keyword,
        songs,
        loading,
        listMode,
        doSearch,
        invalidateSearch
    };
}
