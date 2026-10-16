// src/composables/useLikedSongs.js
// 点赞歌曲列表：仅存于本机 localStorage，不涉及服务器。
// 模块级单例：CenterConsole、VideoEngine 与 SearchModal 共享同一份响应式状态。
import { ref } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';

const likedSongs = ref([]);
let loaded = false;
export const isVideoLike = music => music?.platform === 'bilibili-video' || music?.platform === 'xifan-video';
export const likedSongKey = song => JSON.stringify([song.platform, String(song.id)]);

function load() {
  if (loaded) return;
  loaded = true;
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.LIKED_SONGS);
    const saved = raw ? JSON.parse(raw) : [];
    likedSongs.value = Array.isArray(saved) ? saved
      .filter(song => song && song.id && typeof song.platform === 'string')
      .map(song => ({ ...song, name: String(song.name || ''), artists: Array.isArray(song.artists) ? song.artists.map(String) : [] })) : [];
  } catch {
    likedSongs.value = [];
  }
}

function persist(songs = likedSongs.value) {
  try {
    localStorage.setItem(STORAGE_KEYS.LIKED_SONGS, JSON.stringify(songs));
    return true;
  } catch {
    return false;
  }
}

export function useLikedSongs() {
  load();
  const isLiked = (platform, id) =>
    likedSongs.value.some((s) => s.platform === platform && s.id === id);

  // 把一首歌加入点赞列表（按 platform+id 去重，新赞的排最前）
  const addLikedSong = (music) => {
    load();
    if (!music || !music.id || isLiked(music.platform, music.id)) return;
    likedSongs.value.unshift({
      id: music.id,
      platform: music.platform,
      name: music.name || '',
      artists: music.artists || [],
      coverUrl: music.coverUrl || '',
      // 视频只保留稳定身份和选集信息，不能收藏会过期的媒体地址。
      ...(music.platform==='xifan-video'?{videoSelection:music.videoSelection?{
        provider:music.videoSelection.provider,animeId:music.videoSelection.animeId,
        episodeId:music.videoSelection.episodeId,sourceId:music.videoSelection.sourceId
      }:undefined}:isVideoLike(music)?{}:{url:music.url||''})
    });
    persist();
  };

  const removeLikedSongs = (songs) => {
    load();
    const keys = new Set(songs.map(likedSongKey));
    const next = likedSongs.value.filter(song => !keys.has(likedSongKey(song)));
    if (next.length === likedSongs.value.length) return true;
    if (!persist(next)) return false;
    likedSongs.value = next;
    return true;
  };
  const removeLikedSong = (platform, id) => removeLikedSongs([{platform, id}]);

  return { likedSongs, isLiked, addLikedSong, removeLikedSong, removeLikedSongs };
}
