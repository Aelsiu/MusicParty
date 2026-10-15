import { ref, watch, onBeforeUnmount } from 'vue';
import { videoApi, matchesVideoRead } from '../api/video';
import { roomSession } from '../services/roomSession';
export function useVideoInput(player, keyword, open) {
    const state = ref('KEYWORD'), result = ref(null), error = ref('');
    let sequence = 0, controller, alive = true;
    const invalidate = () => { sequence++; controller?.abort(); controller = null; state.value = 'KEYWORD'; result.value = null; error.value = ''; };
    watch(() => [keyword.value, open.value, roomSession.generation, player.clientGeneration, player.modeEpoch, player.playbackEpoch, player.mediaMode, player.synchronized], (value, previous) => {
        if (!previous || value.some((field, index) => !Object.is(field, previous[index]))) invalidate();
    }, { flush: 'sync' });
    const resolve = async (input, legacyFavorite = false) => {
        invalidate(); const request = sequence, context = player.playbackContext(), room = roomSession.roomId, generation = roomSession.generation;
        if (!context || !open.value) return null;
        controller = new AbortController(); state.value = 'RESOLVING';
        const current = () => alive && request === sequence && generation === roomSession.generation && open.value && player.isContextCurrent(context);
        try {
            const response = legacyFavorite ? await videoApi.preview(input, context, controller.signal).then(favorites => ({ ...favorites, type: 'FAVORITES', favorites })) : await videoApi.resolve(input, context, controller.signal);
            if (!current() || !matchesVideoRead(response, context, player, room)) return null;
            if (!['KEYWORD', 'VIDEO', 'FAVORITES', 'PGC_SEASON', 'PGC_EPISODE'].includes(response.type)) throw new Error('Invalid resolve response');
            if(response.type.startsWith('PGC_') && (!player.videoFeatures.pgcInput || !player.videoFeatures.pgcEpisodeImport)) throw new Error('当前服务不支持番剧选集');
            result.value = response; state.value = response.type === 'VIDEO' ? 'PICK' : response.type;
            return { response, context };
        } catch (failure) {
            if (current()) { state.value = 'ERROR'; error.value = failure.response?.data?.message || '解析失败，请检查输入后重试'; }
            return null;
        }
    };
    onBeforeUnmount(() => { alive = false; invalidate(); });
    return { state, result, error, resolve, invalidate };
}
