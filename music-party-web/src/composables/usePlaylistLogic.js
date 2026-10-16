import { ref, computed, watch, onScopeDispose } from 'vue';
import { useUserStore } from '../stores/user.js';
import { usePlayerStore } from '../stores/player.js';
import { musicApi } from '../api/music.js';
import { useDebounceFn } from '@vueuse/core';
import { useToast } from './useToast';

export function usePlaylistLogic(platformRef, songsRef, listModeRef, loadingRef, feedback = null) {
    const userStore = useUserStore();
    const playerStore = usePlayerStore();
    const { error } = useToast();

    // State
    const playlists = ref([]);
    const currentPlaylistId = ref(null);
    const searchUserKeyword = ref('');
    const userSearchResults = ref([]);
    const isSearchingUser = ref(false);
    const hasSearchedUser = ref(false);
    watch([searchUserKeyword, platformRef], () => {
        hasSearchedUser.value = false;
        userSearchResults.value = [];
    });
    const isPlaylistsLoading = ref(false);
    let playlistRequest = 0;
    let alive = true, lifecycle = 0;
    const capture = () => {
        const generation = playerStore.clientGeneration, epoch = playerStore.modeEpoch, token = lifecycle;
        return () => alive && lifecycle === token && generation === playerStore.clientGeneration && epoch === playerStore.modeEpoch;
    };
    onScopeDispose(() => { alive = false; lifecycle++; });

    // Pagination
    const offset = ref(0);
    const hasMore = ref(true);
    const isLoadingMore = ref(false);
    const limit = computed(() => platformRef.value === 'bilibili' ? 20 : 50);

    const bindings = computed(() => userStore.bindings);

    // 获取用户歌单
    const fetchPlaylists = async () => {
        if (playerStore.mediaMode === 'VIDEO') { playlists.value = []; isPlaylistsLoading.value = false; return; }
        const isCurrent = capture();
        const request = ++playlistRequest;
        const platform = platformRef.value;
        const uid = bindings.value[platform];
        if (!uid) {
            playlists.value = [];
            isPlaylistsLoading.value = false;
            return;
        }
        isPlaylistsLoading.value = true;
        const ticket=feedback?.begin('load');
        try {
            const data = await musicApi.getUserPlaylists(platform, uid);
            if (isCurrent() && request === playlistRequest && platformRef.value === platform && bindings.value[platform] === uid) {playlists.value=data;feedback?.finish(ticket,true);}
        } catch (e) {
            if (!isCurrent() || request !== playlistRequest) return;
            console.error(e);
            playlists.value = [];
            if (e.response?.data?.message) {
                if(feedback)feedback.finish(ticket,false,e.response.data.message);else error(e.response.data.message);
            } else if(feedback)feedback.finish(ticket,false,'歌单读取失败');
        } finally {
            if (isCurrent() && request === playlistRequest) isPlaylistsLoading.value = false;
        }
    };

    // 搜索用户 (用于绑定)
    const searchUser = async () => {
        const isCurrent = capture();
        if (!searchUserKeyword.value) return;
        const keyword = searchUserKeyword.value;
        const platform = platformRef.value;
        isSearchingUser.value = true;
        hasSearchedUser.value = false;
        userSearchResults.value = [];
        const ticket=feedback?.begin('search');
        try {
            const data = await musicApi.searchUser(platform, keyword);
            if (isCurrent() && searchUserKeyword.value === keyword && platformRef.value === platform) {
                userSearchResults.value = data;
                hasSearchedUser.value = true;
                feedback?.finish(ticket,true,data.length?'':'未查到用户');
            }
        } catch (e) {
            if (!isCurrent()) return;
            console.error(e);
            if (e.response?.data?.message) {
                if(feedback)feedback.finish(ticket,false,e.response.data.message);else error(e.response.data.message);
            } else {
                if(feedback)feedback.finish(ticket,false,'用户搜索失败');else error('User Search Failed');
            }
        } finally {
            if (isCurrent()) isSearchingUser.value = false;
        }
    };

    // 绑定用户并刷新
    const bindUser = (user) => {
        if (playerStore.bindAccount(platformRef.value, user.id, user.name, user.avatarUrl) === false) return;
        userSearchResults.value = [];
        hasSearchedUser.value = false;
        searchUserKeyword.value = '';
    };

    // 分页获取歌单歌曲 (核心复杂逻辑)
    const fetchSongsPage = async () => {
        const isCurrent = capture();
        if (!currentPlaylistId.value) return;
        const playlistId = currentPlaylistId.value;
        const platform = platformRef.value;
        const ticket=feedback?.begin('load');
        try {
            const rawSongs = await musicApi.getPlaylistSongs(
                platform,
                playlistId,
                offset.value,
                limit.value
            );
            if (!isCurrent() || currentPlaylistId.value !== playlistId || platformRef.value !== platform) return;

            // B站特殊的分页判定
            if (platformRef.value === 'bilibili') {
                if (rawSongs.length === 0) hasMore.value = false;
            } else {
                if (rawSongs.length < limit.value) hasMore.value = false;
            }

            const validSongs = rawSongs.filter(s => s.id !== 'INVALID_SKIP');
            feedback?.finish(ticket,true);
            songsRef.value.push(...validSongs);

            // 贪婪加载：如果有效数据太少，自动加载下一页
            if (hasMore.value && rawSongs.length > 0 && validSongs.length < 10) {
                offset.value += limit.value;
                await fetchSongsPage();
            }

            // 兜底
            if (rawSongs.length === 0) isLoadingMore.value = false;

        } catch (e) {
            if (!isCurrent()) return;
            console.error("Fetch songs failed", e);
            feedback?.finish(ticket,false,e.response?.data?.message||'歌单读取失败');
            if (currentPlaylistId.value === playlistId && platformRef.value === platform) hasMore.value = false;
        }
    };

    // 本地筛选需要扫描整张歌单；沿用现有分页接口逐页补齐。
    const loadRemainingSongs = async () => {
        const isCurrent = capture();
        if (!currentPlaylistId.value || !hasMore.value || isLoadingMore.value || loadingRef.value) return;
        const playlistId = currentPlaylistId.value;
        const platform = platformRef.value;
        isLoadingMore.value = true;
        try {
            while (isCurrent() && hasMore.value && currentPlaylistId.value === playlistId && platformRef.value === platform) {
                offset.value += limit.value;
                await fetchSongsPage();
            }
        } finally {
            if (isCurrent()) isLoadingMore.value = false;
        }
    };

    // 点击歌单
    const loadPlaylist = async (pid) => {
        lifecycle++;
        const isCurrent = capture();
        listModeRef.value = 'playlist';
        currentPlaylistId.value = pid;
        songsRef.value = [];
        offset.value = 0;
        hasMore.value = true;
        loadingRef.value = true;

        try {
            await fetchSongsPage();
        } finally {
            if (isCurrent()) loadingRef.value = false;
        }
    };

    // 滚动加载
    const handleScroll = useDebounceFn(async (e) => {
        const isCurrent = capture();
        if (listModeRef.value !== 'playlist') return;
        const el = e.target;
        const bottom = el.scrollHeight - el.scrollTop - el.clientHeight;

        if (bottom < 100 && hasMore.value && !isLoadingMore.value && !loadingRef.value) {
            isLoadingMore.value = true;
            offset.value += limit.value;
            try {
                await fetchSongsPage();
            } finally {
                if (isCurrent()) isLoadingMore.value = false;
            }
        }
    }, 200);

    const hydrateBoundProfile = async () => {
        if (playerStore.mediaMode === 'VIDEO') return;
        const platform = platformRef.value, id = bindings.value[platform];
        const nameField = platform === 'netease' ? 'neteaseUsername' : 'bilibiliUsername';
        const avatarField = platform === 'netease' ? 'neteaseAvatar' : 'bilibiliAvatar';
        const name = userStore[nameField], isCurrent = capture();
        if (!id || !name || userStore[avatarField]) return;
        try {
            const users = await musicApi.searchUser(platform, name);
            if (!isCurrent() || platformRef.value !== platform || bindings.value[platform] !== id || userStore[nameField] !== name || userStore[avatarField]) return;
            const match = users.find(user => String(user.id) === String(id));
            if (match) userStore.updateBinding(platform, match.id, match.name, match.avatarUrl);
        } catch { /* 资料补全失败保留本机绑定，歌单读取独立进行。 */ }
    };

    // 搜索页本地绑定变化（含其他标签页）刷新歌单。
    watch(() => [platformRef.value, bindings.value[platformRef.value], playerStore.modeEpoch, playerStore.clientGeneration], () => {
        lifecycle++; playlistRequest++; playlists.value = []; userSearchResults.value = []; hasSearchedUser.value = false;
        isSearchingUser.value = false; isPlaylistsLoading.value = false; isLoadingMore.value = false; loadingRef.value = false;
        currentPlaylistId.value = null;
        if (listModeRef.value === 'playlist') songsRef.value = [];
        fetchPlaylists();
        hydrateBoundProfile();
    }, { immediate: true });

    return {
        playlists,
        currentPlaylistId,
        searchUserKeyword,
        userSearchResults,
        isSearchingUser,
        hasSearchedUser,
        isPlaylistsLoading,
        hasMore,
        isLoadingMore,
        bindings,
        fetchPlaylists,
        searchUser,
        bindUser,
        loadPlaylist,
        loadRemainingSongs,
        handleScroll
    };
}
