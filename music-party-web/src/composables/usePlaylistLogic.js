import { ref, computed, watch } from 'vue';
import { useUserStore } from '../stores/user.js';
import { usePlayerStore } from '../stores/player.js';
import { musicApi } from '../api/music.js';
import { useDebounceFn } from '@vueuse/core';
import { useToast } from './useToast';

export function usePlaylistLogic(platformRef, songsRef, listModeRef, loadingRef) {
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

    // Pagination
    const offset = ref(0);
    const hasMore = ref(true);
    const isLoadingMore = ref(false);
    const limit = computed(() => platformRef.value === 'bilibili' ? 20 : 50);

    const bindings = computed(() => userStore.bindings);

    // 获取用户歌单
    const fetchPlaylists = async () => {
        const platform = platformRef.value;
        const uid = bindings.value[platform];
        if (!uid) {
            playlists.value = [];
            return;
        }
        isPlaylistsLoading.value = true;
        try {
            const data = await musicApi.getUserPlaylists(platform, uid);
            if (platformRef.value === platform && bindings.value[platform] === uid) playlists.value = data;
        } catch (e) {
            console.error(e);
            playlists.value = [];
            if (e.response?.data?.message) {
                error(e.response.data.message);
            }
        } finally {
            isPlaylistsLoading.value = false;
        }
    };

    // 搜索用户 (用于绑定)
    const searchUser = async () => {
        if (!searchUserKeyword.value) return;
        const keyword = searchUserKeyword.value;
        const platform = platformRef.value;
        isSearchingUser.value = true;
        hasSearchedUser.value = false;
        userSearchResults.value = [];
        try {
            const data = await musicApi.searchUser(platform, keyword);
            if (searchUserKeyword.value === keyword && platformRef.value === platform) {
                userSearchResults.value = data;
                hasSearchedUser.value = true;
            }
        } catch (e) {
            console.error(e);
            if (e.response?.data?.message) {
                error(e.response.data.message);
            } else {
                error('User Search Failed');
            }
        } finally {
            isSearchingUser.value = false;
        }
    };

    // 绑定用户并刷新
    const bindUser = (user) => {
        playerStore.bindAccount(platformRef.value, user.id, user.name, user.avatarUrl);
        userSearchResults.value = [];
        hasSearchedUser.value = false;
        searchUserKeyword.value = '';
    };

    // 分页获取歌单歌曲 (核心复杂逻辑)
    const fetchSongsPage = async () => {
        if (!currentPlaylistId.value) return;
        const playlistId = currentPlaylistId.value;
        const platform = platformRef.value;
        try {
            const rawSongs = await musicApi.getPlaylistSongs(
                platform,
                playlistId,
                offset.value,
                limit.value
            );
            if (currentPlaylistId.value !== playlistId || platformRef.value !== platform) return;

            // B站特殊的分页判定
            if (platformRef.value === 'bilibili') {
                if (rawSongs.length === 0) hasMore.value = false;
            } else {
                if (rawSongs.length < limit.value) hasMore.value = false;
            }

            const validSongs = rawSongs.filter(s => s.id !== 'INVALID_SKIP');
            songsRef.value.push(...validSongs);

            // 贪婪加载：如果有效数据太少，自动加载下一页
            if (hasMore.value && rawSongs.length > 0 && validSongs.length < 10) {
                offset.value += limit.value;
                await fetchSongsPage();
            }

            // 兜底
            if (rawSongs.length === 0) isLoadingMore.value = false;

        } catch (e) {
            console.error("Fetch songs failed", e);
            if (currentPlaylistId.value === playlistId && platformRef.value === platform) hasMore.value = false;
        }
    };

    // 本地筛选需要扫描整张歌单；沿用现有分页接口逐页补齐。
    const loadRemainingSongs = async () => {
        if (!currentPlaylistId.value || !hasMore.value || isLoadingMore.value || loadingRef.value) return;
        const playlistId = currentPlaylistId.value;
        const platform = platformRef.value;
        isLoadingMore.value = true;
        try {
            while (hasMore.value && currentPlaylistId.value === playlistId && platformRef.value === platform) {
                offset.value += limit.value;
                await fetchSongsPage();
            }
        } finally {
            isLoadingMore.value = false;
        }
    };

    // 点击歌单
    const loadPlaylist = async (pid) => {
        listModeRef.value = 'playlist';
        currentPlaylistId.value = pid;
        songsRef.value = [];
        offset.value = 0;
        hasMore.value = true;
        loadingRef.value = true;

        try {
            await fetchSongsPage();
        } finally {
            loadingRef.value = false;
        }
    };

    // 滚动加载
    const handleScroll = useDebounceFn(async (e) => {
        if (listModeRef.value !== 'playlist') return;
        const el = e.target;
        const bottom = el.scrollHeight - el.scrollTop - el.clientHeight;

        if (bottom < 100 && hasMore.value && !isLoadingMore.value && !loadingRef.value) {
            isLoadingMore.value = true;
            offset.value += limit.value;
            try {
                await fetchSongsPage();
            } finally {
                isLoadingMore.value = false;
            }
        }
    }, 200);

    // 设置窗口中的绑定/解绑也同步刷新搜索页。
    watch(() => [platformRef.value, bindings.value[platformRef.value]], () => {
        currentPlaylistId.value = null;
        if (listModeRef.value === 'playlist') songsRef.value = [];
        fetchPlaylists();
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
        searchUser,
        bindUser,
        loadPlaylist,
        loadRemainingSongs,
        handleScroll
    };
}
