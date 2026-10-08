import { ref, watch, onScopeDispose } from 'vue';
import { useToast } from './useToast';
import { musicApi } from '../api/music.js';
import { usePlayerStore } from '../stores/player';

export function useSearchLogic(emit) {
    const { error } = useToast();

    const player = usePlayerStore();
    const platform = ref(player.mediaMode === 'VIDEO' ? 'bilibili' : 'netease');
    let requestSequence = 0, alive = true;
    const keyword = ref('');
    const songs = ref([]);
    const loading = ref(false);
    const listMode = ref('search'); // 'search' | 'playlist'
    watch(() => [player.mediaMode, player.modeEpoch, player.clientGeneration], () => {
        requestSequence++; songs.value = []; keyword.value = ''; loading.value = false; listMode.value = 'search';
        if (player.mediaMode === 'VIDEO') platform.value = 'bilibili';
    }, { flush: 'sync' });
    onScopeDispose(() => { alive = false; requestSequence++; });

    const doSearch = async () => {
        const val = keyword.value.trim();
        if (!val) return;
        const request = ++requestSequence, sourcePlatform = platform.value;
        const generation = player.clientGeneration, modeEpoch = player.modeEpoch;
        const isCurrent = () => alive && request === requestSequence && platform.value === sourcePlatform
            && player.clientGeneration === generation && player.modeEpoch === modeEpoch;

        // 普通搜索
        listMode.value = 'search';
        loading.value = true;
        songs.value = [];
        try {
            const data = await musicApi.search(sourcePlatform, val);
            if (isCurrent()) songs.value = data;
        } catch (e) {
            if (!isCurrent()) return;
            const msg = e.response?.data?.message || 'Search Failed';
            error(msg);
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
        doSearch
    };
}
