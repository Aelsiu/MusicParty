<template>
  <div v-show="isOpen" :inert="!isOpen" class="fixed inset-0 z-[60] bg-overlay/80 backdrop-blur-sm flex items-center justify-center p-0 md:p-4" @keydown.esc.stop="escapeSearch">
    <!-- 移动端全屏面板；桌面端居中弹窗。安全区 padding 兼容刘海屏/全面屏 -->
    <div class="w-full max-w-4xl bg-medical-50 h-full md:h-[80vh] flex flex-col shadow-2xl relative chamfer-br max-h-full pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">

      <!-- 关闭按钮 (移动端避开刘海区域) -->
      <button @click="closeSearch" aria-label="关闭搜索" class="absolute top-[env(safe-area-inset-top)] right-0 p-4 hover:text-accent z-50">
        <X class="w-6 h-6" />
      </button>

      <!-- 头部 -->
      <div class="search-header p-3 md:p-4 bg-surface flex-shrink-0">
        <h2 class="text-xl font-bold font-mono mb-2 text-medical-900 flex items-center gap-2">
          <Search class="w-5 h-5 text-accent"/> SEARCH
        </h2>

        <!-- 平台切换 TAB (移动端收紧内边距，防小屏换行)；全长方形，选中用主题色高亮 -->
        <div class="flex gap-1 mb-2 flex-wrap">
          <button
              v-for="p in (playerStore.mediaMode === 'VIDEO' ? ['bilibili','xifan'] : ['netease', 'bilibili'])" :key="p"
              @click="selectPlatform(p)"
              :aria-label="p.toUpperCase()"
              :disabled="!isPlatformEnabled(p)"
              class="px-4 md:px-6 py-1.5 text-sm font-bold uppercase transition-all"
              :class="[
                mode === 'search' && platform === p ? 'bg-accent text-white' : 'bg-medical-200 text-medical-500 hover:bg-medical-300',
                !isPlatformEnabled(p) ? 'opacity-30 cursor-not-allowed grayscale' : ''
              ]"
          >
            {{ p }}
          </button>
          <!-- 本地点赞列表标签（仅本地缓存，不上报服务器） -->
          <button
              v-if="playerStore.mediaMode === 'MUSIC'"
              @click="mode = 'likesong'"
              class="px-4 md:px-6 py-1.5 text-sm font-bold transition-all"
              :class="mode === 'likesong' ? 'bg-accent text-white' : 'bg-medical-200 text-medical-500 hover:bg-medical-300'"
          >
            LIKESONG
          </button>
        </div>

        <!-- 搜索框 -->
        <div class="flex gap-2">
          <input
              v-model="keyword"
              aria-label="搜索关键词或视频链接"
              @keyup.enter="handleSearchAction"
              :placeholder="playerStore.mediaMode==='VIDEO' && platform==='xifan' ? '搜索番剧，或粘贴 XIFAN 官网作品/播放链接' : playerStore.mediaMode === 'VIDEO' ? '搜索视频，或粘贴 BV/AV号、视频链接、短链接、收藏夹链接和分享文字' : mode === 'likesong' ? '搜索点赞的歌曲...' : '在曲库中搜索...'"
              class="placeholder-mono flex-1 min-w-0 border px-3 py-2 outline-none transition-colors duration-300 font-sans bg-medical-100 border-medical-200 focus:border-accent"
          />
          <button
              @click="handleSearchAction"
              :disabled="feedback.pending.value"
              class="text-medical-50 px-3 md:px-5 py-2 font-bold transition-colors text-xs md:text-sm flex-shrink-0 font-sans bg-accent hover:bg-accent-hover disabled:opacity-40"
          >
            SEARCH
          </button>
        </div>
      </div>
      <div class="search-feedback-slot" aria-live="polite" data-search-feedback>
        <span v-if="feedback.state.value" class="search-feedback-text font-sans" :class="{'search-feedback-failed':feedback.state.value.success===false}"><Search/><span class="search-feedback-label" :class="{'is-pending':feedback.state.value.pending}"><span v-for="(char,index) in Array.from(feedback.state.value.text)" :key="index" class="search-feedback-char" :style="{'--char-index':index}">{{char}}</span></span><button v-if="feedback.state.value.details" type="button" class="search-feedback-help" aria-label="查看详细响应" :aria-expanded="feedback.detailsOpen.value" aria-controls="search-feedback-details" @click="feedback.detailsOpen.value=!feedback.detailsOpen.value"><CircleHelp aria-hidden="true"/></button></span>
        <div v-if="feedback.state.value&&feedback.detailsOpen.value" id="search-feedback-details" class="search-feedback-details" role="status">{{feedback.state.value.details}}</div>
      </div>
      <VideoSearchPanel v-if="playerStore.mediaMode==='VIDEO'" ref="videoSearch" :platform="platform" :keyword="keyword" :songs="songs" :do-search="doSearch"/>
      <div v-else class="flex-1 overflow-hidden flex flex-col md:flex-row relative">

        <!-- LikeSong：本地点赞歌曲列表（仅本地缓存，不上报服务器） -->
        <div v-if="mode === 'likesong'" class="flex-1 bg-medical-50 flex-col min-h-0 flex">
          <div class="flex items-center justify-between p-3 bg-surface border-b border-medical-200 flex-shrink-0">
            <span class="font-bold text-sm text-medical-800 font-mono">LIKESONG</span>
            <span class="text-[10px] font-mono text-medical-400">{{ likedSongs.length }} LIKED</span>
          </div>
          <div class="flex-1 overflow-y-auto overscroll-contain p-2 md:p-4">
            <div class="space-y-1">
              <div v-for="song in filteredLikedSongs" :key="song.platform + ':' + song.id" class="flex items-center p-3 bg-surface border border-transparent transition-all group hover:border-medical-300 hover:shadow-sm">
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" :scanline="false" /></div>
                  <div class="min-w-0 flex-1">
                    <OverflowText as="div" class="text-sm font-bold " :text="String(song.name)" />
                    <OverflowText as="div" class="text-xs text-medical-500 " :text="String(song.artists.join(' / '))" />
                  </div>
                </div>
                <button
                    @click="addLikedClick(song)"
                    :disabled="isInQueue(song.id) || pendingIds.has(song.id)"
                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                    :class="[
                        isInQueue(song.id) ? 'text-green-500 cursor-default' :
                        pendingIds.has(song.id) ? 'text-accent cursor-wait' :
                        'text-medical-300 hover:text-accent'
                    ]"
                >
                  <!-- 状态 1: 正在添加 -->
                  <Loader2 v-if="pendingIds.has(song.id)" class="w-5 h-5 animate-spin" />
                  <!-- 状态 2: 已经在队列中 -->
                  <Check v-else-if="isInQueue(song.id)" class="w-5 h-5" />
                  <!-- 状态 3: 普通添加按钮 -->
                  <PlusCircle v-else class="w-5 h-5"/>
                </button>
                <button
                    @click="openLikedSource(song)"
                    title="打开源页面"
                    class="ml-1 p-2 flex-shrink-0 text-medical-300 hover:text-accent transition-all duration-300"
                >
                  <ExternalLink class="w-5 h-5" />
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 左侧：我的歌单 / 绑定 -->
        <div v-if="playerStore.mediaMode !== 'VIDEO'" class="md:w-1/3 md:h-auto flex-shrink-0 border-b md:border-b-0 md:border-r border-medical-200 flex-col bg-surface transition-all"
             :class="mode === 'likesong' ? 'hidden' : (mobileView === 'playlists' ? 'flex w-full h-full' : 'hidden md:flex')"
        >
          <div class="flex-1 overflow-y-auto overscroll-contain p-2 space-y-2">
            <!-- 未绑定 -->
            <div v-if="!bindings[platform]" class="p-4 border border-dashed border-medical-300 bg-medical-50">
              <div class="info-tip text-xs text-medical-500 mb-2 text-center font-sans">绑定用户以获取用户歌单</div>
              <div class="flex gap-1">
                <input v-model="searchUserKeyword" @keyup.enter="searchUser" placeholder="搜索用户名" class="flex-1 min-w-0 bg-surface border border-medical-200 p-1 text-sm outline-none focus:border-accent font-sans" />
                <button @click="searchUser" class="bg-accent hover:bg-accent-hover p-1 transition-colors"><Search class="w-4 h-4 text-white"/></button>
              </div>
              <!-- 搜索结果列表 -->
              <div v-if="userSearchResults.length > 0" class="mt-2 max-h-40 overflow-y-auto space-y-1 border-t border-medical-200 pt-2">
                <div v-for="user in userSearchResults" :key="user.id" @click="bindUser(user)" class="flex items-center gap-2 p-1 hover:bg-accent/10 cursor-pointer group">
                  <img :src="user.avatarUrl" class="w-6 h-6 rounded-full bg-medical-200"/>
                  <OverflowText as="span" class="text-xs font-bold  flex-1" :text="String(user.name)" />
                </div>
              </div>
              <div v-if="isSearchingUser" class="text-center py-2"><Loader2 class="w-4 h-4 animate-spin mx-auto text-accent"/></div>

            </div>

            <!-- 已绑定：歌单列表 -->
            <template v-else>
              <div class="flex justify-between items-center px-2 py-1 bg-medical-50 border-b border-medical-100">
                <OverflowText as="span" class="text-[10px] font-mono text-medical-400 " :text="String((platform === 'netease' ? userStore.neteaseUsername : userStore.bilibiliUsername) || `ID: ${bindings[platform]}`)" />
                <div class="flex items-center gap-2 shrink-0">
                  <button @click="fetchPlaylists" :disabled="isPlaylistsLoading" aria-label="刷新用户歌单" class="p-1 text-medical-400 hover:text-accent disabled:opacity-40"><RefreshCw class="w-3 h-3" :class="{ 'animate-spin': isPlaylistsLoading }" /></button>
                  <button @click="playerStore.bindAccount(platform, '')" class="text-[10px] text-red-400 hover:underline">UNLINK</button>
                </div>
              </div>

              <div v-if="isPlaylistsLoading" class="flex justify-center py-8">
                <Loader2 class="w-6 h-6 animate-spin text-accent" />
              </div>

              <div v-else v-for="pl in playlists" :key="pl.id" @click="handleSelectPlaylist(pl.id)" class="flex items-center gap-3 p-2 hover:bg-medical-50 cursor-pointer group transition-colors border-l-2 border-transparent hover:border-accent">
                <div class="w-10 h-10 bg-medical-200 flex-shrink-0 overflow-hidden"><CoverImage :src="pl.coverImgUrl" class="w-full h-full" :scanline="false" /></div>
                <div class="overflow-hidden">
                  <OverflowText as="div" class="text-sm font-bold  group-hover:text-accent" :text="String(pl.name)" />
                  <div class="text-xs font-mono text-medical-400">{{ pl.trackCount }} TRACKS</div>
                </div>
                <ChevronRight class="w-4 h-4 text-medical-300 md:hidden ml-auto" />
              </div>
            </template>
          </div>
        </div>

        <!-- 右侧：歌曲列表 -->
        <div class="md:flex-1 bg-medical-50 flex-col min-h-0 min-w-0" :class="mode === 'likesong' ? 'hidden' : (mobileView === 'songs' ? 'flex w-full h-full' : 'hidden md:flex')">
          <!-- 移动端返回条 -->
          <div class="md:hidden flex items-center gap-2 p-3 bg-surface border-b border-medical-200 flex-shrink-0">
            <button @click="mobileView = 'playlists'" class="p-1 -ml-1 text-medical-500 hover:text-medical-900"><ArrowLeft class="w-5 h-5" /></button>
            <span class="font-bold text-sm text-medical-800">{{ listMode === 'search' ? 'SEARCH RESULTS' : 'PLAYLIST DETAILS' }}</span>
          </div>

          <!-- 歌单导航栏固定在右侧内容区顶部，歌曲列表单独滚动。 -->
          <div v-if="currentPlaylistId && listMode === 'playlist' && !loading" class="flex-shrink-0 bg-surface border-b border-medical-200 px-3 py-2 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
            <div class="min-w-0">
              <div class="text-[10px] font-sans text-medical-400">歌单内容</div>
              <OverflowText as="div" class="font-bold text-base " :text="String(currentPlaylistId)" />
              <div class="text-[10px] text-medical-400 font-mono">{{ songs.length }} LOADED<span v-if="playlistFilterKeyword"> · {{ displayedSongs.length }} MATCHED</span></div>
            </div>
            <div class="flex gap-2 w-full sm:w-auto flex-shrink-0 justify-end">
              <div class="playlist-import-actions flex flex-none gap-2">
                <button @click="handleImportPlaylist" aria-label="导入全部" class="import-all flex-none h-6 px-[3px] overflow-hidden bg-strong text-white text-[10px] font-bold hover:bg-accent transition-[width,background-color] duration-200 flex items-center gap-1 font-sans">
                  <PlaylistImportIcon variant="all" class="w-[18px] h-[18px] shrink-0" />
                  <span class="shrink-0 whitespace-nowrap transition-opacity">ADD ALL</span>
                </button>
                <button @click="handleImportSelected" :disabled="!importableSelectedSongs.length" :aria-label="`导入已选（${importableSelectedSongs.length} 首）`" class="import-selected flex-none h-6 px-[3px] overflow-hidden bg-strong text-white text-[10px] font-bold hover:bg-accent transition-[width,background-color] duration-200 disabled:opacity-40 disabled:cursor-not-allowed flex items-center gap-1 font-sans">
                  <PlaylistImportIcon variant="selected" class="w-[18px] h-[18px] shrink-0" />
                  <span class="shrink-0 whitespace-nowrap transition-opacity">SELECTED</span>
                </button>
              </div>
              <input v-model="playlistFilterInput" type="search" aria-label="筛选歌单歌曲" placeholder="筛选歌曲" class="w-[102px] h-6 flex-none border border-medical-200 bg-medical-50 px-2 text-[10px] outline-none focus:border-accent" />
            </div>
          </div>

          <div @scroll="handleScroll" class="flex-1 min-h-0 overflow-y-auto overscroll-contain p-2 md:p-4">


            <!-- 歌曲列表渲染 -->
            <div class="space-y-1">

              <div v-for="song in displayedSongs" :key="song.id" class="flex items-center p-3 border border-transparent transition-all group" :class="isUnplayable(song) ? 'opacity-50 grayscale bg-medical-50 cursor-not-allowed' : 'bg-surface hover:border-medical-300 hover:shadow-sm'">
                <input v-if="listMode === 'playlist'" type="checkbox" :aria-label="`选中歌曲 ${song.name}`" :checked="selectedSongIds.has(song.id)" :disabled="isUnplayable(song) || isInQueue(song.id) || pendingIds.has(song.id)" @change="toggleSelectedSong(song.id, $event.target.checked)" class="playlist-song-checkbox mr-3 h-4 w-4 flex-shrink-0 cursor-pointer disabled:cursor-not-allowed" />
                <div class="flex-1 w-0 flex items-center gap-3">
                  <div class="w-8 h-8 bg-medical-200 flex-shrink-0 relative overflow-hidden"><CoverImage :src="song.coverUrl" class="w-full h-full" :scanline="false" /></div>
                  <div class="min-w-0 flex-1">
                    <OverflowText as="div" class="text-sm font-bold " :text="String(song.name)" />
                    <OverflowText as="div" class="text-xs text-medical-500 " :text="String(song.artists.join(' / '))" />
                  </div>
                </div>
                <div v-if="isUnplayable(song)" class="ml-2 flex-shrink-0"><span class="px-1.5 py-0.5 text-[10px] font-mono font-bold text-medical-400 border border-medical-300 bg-medical-100 rounded-sm">>{{ (playerStore.config.bilibiliMaxDurationMinutes || 10) }}MIN</span></div>

                <button
                    v-else
                    @click="handleAddClick(song)"
                    class="ml-2 p-2 flex-shrink-0 transition-all duration-300"
                    :class="[
                        isInQueue(song.id) ? 'text-green-500 cursor-default' :
                        pendingIds.has(song.id) ? 'text-accent cursor-wait' :
                        'text-medical-300 hover:text-accent'
                    ]"
                    :disabled="pendingIds.has(song.id) || isInQueue(song.id)"
                >
                  <!-- 状态 1: 正在添加 -->
                  <Loader2 v-if="pendingIds.has(song.id)" class="w-5 h-5 animate-spin" />

                  <!-- 状态 2: 已经在队列中 -->
                  <Check v-else-if="isInQueue(song.id)" class="w-5 h-5" />

                  <!-- 状态 3: 普通添加按钮 -->
                  <PlusCircle v-else class="w-5 h-5"/>
                </button>
              </div>
            </div>

            <!-- 加载更多 -->
            <div v-if="listMode === 'playlist' && !loading" class="py-4 text-center">
              <div v-if="isLoadingMore" class="flex justify-center text-accent"><Loader2 class="w-6 h-6 animate-spin" /></div>

            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import OverflowText from './OverflowText.vue';
import { ref, computed, watch, onBeforeUnmount, provide, nextTick } from 'vue';
import VideoSearchPanel from './VideoSearchPanel.vue';
import {useSearchFeedback,searchFeedbackKey,setSearchEventHandler,observeEnqueue} from '../composables/useSearchFeedback';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useSearchLogic } from '../composables/useSearchLogic';
import { usePlaylistLogic } from '../composables/usePlaylistLogic';
import { useLikedSongs } from '../composables/useLikedSongs';
import { matchesPlaylistSong } from '../utils/playlistFilter';
import { videoSourcePage } from '../utils/playbackProtocol';
import { X, Search, CircleHelp, PlusCircle, Loader2, ArrowLeft, ChevronRight, Check, ExternalLink, RefreshCw } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import PlaylistImportIcon from './PlaylistImportIcon.vue';

const props = defineProps(['isOpen']);
const emit = defineEmits(['close']);
const playerStore = usePlayerStore();
const userStore = useUserStore();
const feedback=useSearchFeedback(),videoSearch=ref(null);
provide(searchFeedbackKey,feedback);
const backSelection=()=>playerStore.mediaMode==='VIDEO'&&videoSearch.value?.back()===true;
const closeSearch=()=>emit('close');
const escapeSearch=()=>{if(!backSelection())closeSearch();};
defineExpose({backSelection});
const releaseSearchEvents=setSearchEventHandler((event,token)=>{
  const queuePending=feedback.state.value?.kind==='queue'&&feedback.state.value.pending;
  if(!props.isOpen&&!queuePending)return false;
  if(queuePending&&event.type?.toUpperCase()==='ERROR'&&event.userId==='SYSTEM'&&event.action!=='ERROR_LOAD'){
    feedback.finish(feedback.state.value.ticket,false,event.message||event.payload||'入队失败');return true;
  }
  if(event.userId!==token)return false;
  if(event.action==='IMPORT_PLAYLIST'&&queuePending){
    feedback.finish(feedback.state.value.ticket,event.type?.toUpperCase()==='SUCCESS'&&Number(event.payload)>0,event.payload?`已入队 ${event.payload} 项`:'没有项目入队');return true;
  }
  if(['ADD','ADD_PLAYLIST'].includes(event.action))return true;
  if(event.action==='SYSTEM_MESSAGE'&&queuePending&&['ERROR','WARN'].includes(event.type?.toUpperCase())){feedback.finish(feedback.state.value.ticket,false,event.message||event.payload||'入队失败');return true;}
  return false;
});
onBeforeUnmount(releaseSearchEvents);
const enqueuePlatform = computed(() => playerStore.mediaMode === 'VIDEO' ? 'bilibili-video' : platform.value);

const isPlatformEnabled = (p) => {
  if(p==='xifan') return playerStore.videoFeatures.xifanSearch===true && playerStore.config.xifanEnabled!==false;
  if (p === 'netease') return playerStore.config?.neteaseEnabled !== false;
  if (p === 'bilibili') return playerStore.config?.bilibiliEnabled !== false;
  return true;
};

// 1. 引入搜索逻辑
const {
  platform, keyword, songs, loading, listMode, doSearch, invalidateSearch
} = useSearchLogic(emit,feedback);
const handleSearchAction=async()=>{
  if(playerStore.mediaMode==='VIDEO'){await videoSearch.value?.search();return;}
  if(mode.value==='likesong'){const ticket=feedback.begin('search');feedback.finish(ticket,true);return;}
  await doSearch();mobileView.value='songs';
};

// LikeSong 标签：'search' = 平台搜索 / 歌单，'likesong' = 本地点赞列表
const mode = ref('search');
const { likedSongs } = useLikedSongs();

// LIKESONG 下搜索：按关键词过滤本地点赞记录（匹配歌名或歌手，不区分大小写）
const filteredLikedSongs = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return likedSongs.value;
  return likedSongs.value.filter((s) =>
    s.name.toLowerCase().includes(kw) ||
    (s.artists || []).some((a) => String(a).toLowerCase().includes(kw))
  );
});

const selectPlatform=async p=>{
  if(!isPlatformEnabled(p)||mode.value==='search'&&platform.value===p)return;
  invalidateSearch();feedback.clear();platform.value=p;mode.value='search';
  await nextTick();
  if(playerStore.mediaMode==='VIDEO'){await videoSearch.value?.search();return;}
  if(keyword.value.trim())await doSearch();
};

// 2. 引入歌单逻辑 (注入依赖)
const {
  playlists, currentPlaylistId, searchUserKeyword, userSearchResults,
  isSearchingUser, hasSearchedUser, isPlaylistsLoading, hasMore, isLoadingMore, bindings,
  searchUser, bindUser, fetchPlaylists, loadPlaylist, loadRemainingSongs, handleScroll
} = usePlaylistLogic(platform, songs, listMode, loading,feedback);

// 3. UI 状态
const mobileView = ref('playlists');
const playlistFilterInput = ref('');
const playlistFilterKeyword = ref('');
const selectedSongIds = ref(new Set());
const displayedSongs = computed(() => listMode.value === 'playlist' && playlistFilterKeyword.value
  ? songs.value.filter(song => matchesPlaylistSong(song, playlistFilterKeyword.value))
  : songs.value);

watch(playlistFilterInput, (value, _, onCleanup) => {
  const timer = setTimeout(() => { playlistFilterKeyword.value = value.trim(); }, 250);
  onCleanup(() => clearTimeout(timer));
});
watch([platform, currentPlaylistId], () => {
  selectedSongIds.value = new Set();
  playlistFilterInput.value = '';
  playlistFilterKeyword.value = '';
});
watch([playlistFilterKeyword, loading, isLoadingMore], () => {
  if (listMode.value === 'playlist' && playlistFilterKeyword.value && !loading.value && !isLoadingMore.value) loadRemainingSongs();
});

// 4. 交互胶水代码
const isUnplayable = (song) => {
  if (playerStore.mediaMode === 'VIDEO') return false;
  // B站视频时长上限（分钟）由管理面板配置，默认 10
  const maxMin = playerStore.config.bilibiliMaxDurationMinutes || 10;
  return platform.value === 'bilibili' && song.duration > maxMin * 60 * 1000;
};

const handleSelectPlaylist = (pid) => {
  selectedSongIds.value = new Set();
  playlistFilterInput.value = '';
  playlistFilterKeyword.value = '';
  loadPlaylist(pid);
  mobileView.value = 'songs';
};

const handleImportPlaylist = () => {
  const ticket=feedback.begin('queue');
  if(!playerStore.enqueuePlaylist(enqueuePlatform.value,currentPlaylistId.value)){feedback.finish(ticket,false,'无法发送入队请求');return;}
  const timer=setTimeout(()=>{pendingTimers.delete(timer);feedback.finish(ticket,false,'结果暂无法确认，请核对播放队列');},35000);pendingTimers.add(timer);
};

const importableSelectedSongs = computed(() => songs.value.filter(song =>
  selectedSongIds.value.has(song.id) && !isUnplayable(song) && !isInQueue(song.id) && !pendingIds.value.has(song.id)
));
const toggleSelectedSong = (id, checked) => {
  const next = new Set(selectedSongIds.value);
  if (checked) next.add(id);
  else next.delete(id);
  selectedSongIds.value = next;
};
const handleImportSelected = async () => {
  if (!importableSelectedSongs.value.length) return;
  const snapshot=importableSelectedSongs.value.slice();selectedSongIds.value=new Set();
  const ticket=feedback.begin('queue');
  const results=await Promise.all(snapshot.map(song=>queueOne(song,enqueuePlatform.value,false,ticket)));
  feedback.finish(ticket,results.every(Boolean),results.every(Boolean)?'':'部分项目未确认入队，请核对播放队列');
};

// 本地维护正在添加中的歌曲 ID 集合
const pendingIds = ref(new Set());
const pendingTimers=new Set();
const enqueueObservers=new Set();
onBeforeUnmount(()=>{pendingTimers.forEach(clearTimeout);pendingTimers.clear();enqueueObservers.forEach(outcome=>outcome.cancel());enqueueObservers.clear();});
async function queueOne(song,sourcePlatform,recorded=false,groupTicket=null){
  if(pendingIds.value.has(song.id)||isInQueue(song.id))return false;
  const context=playerStore.playbackContext();if(!context){feedback.fail('queue','房间连接尚未就绪');return false;}
  const ticket=groupTicket??feedback.begin('queue');pendingIds.value.add(song.id);
  const outcome=observeEnqueue(playerStore,song.id,context);
  enqueueObservers.add(outcome);
  const sent=recorded?await playerStore.enqueueRecorded(song,message=>feedback.finish(ticket,false,message)):playerStore.enqueue(sourcePlatform,song.id,context);
  if(!sent){outcome.cancel();enqueueObservers.delete(outcome);pendingIds.value.delete(song.id);if(groupTicket===null)feedback.finish(ticket,false,'请先设置昵称并确认房间连接');return false;}
  outcome.check();const success=await outcome.promise;
  enqueueObservers.delete(outcome);
  pendingIds.value.delete(song.id);if(groupTicket===null)feedback.finish(ticket,success,success?'':'结果暂无法确认，请核对播放队列');return success;
}

// 检查歌曲是否已经在队列中
const isInQueue = (songId) => {
  if (playerStore.mediaMode === 'VIDEO' && !/:p\d+$/.test(songId)) return false;
  const id = songId;
  return playerStore.queue.some(item => item.music.id === id);
};

// 处理点击添加
const handleAddClick=song=>queueOne(song,enqueuePlatform.value);
const addLikedClick=song=>queueOne(song,song.platform,true);

// LikeSong 列表：跳转源页面（与播放控制器专辑封面逻辑一致）
const openLikedSource = (song) => {
  const url = song.platform === 'netease'
    ? `https://music.163.com/#/song?id=${song.id}`
    : videoSourcePage(song.id,song.videoSelection);
  if (url) window.open(url, '_blank');
};

// 监听搜索动作 -> 自动切视图
watch(listMode, (m) => {
  if (m === 'search') mobileView.value = 'songs';
});

watch(() => props.isOpen, (val) => {
  if (val) mobileView.value = playerStore.mediaMode === 'VIDEO' ? 'songs' : 'playlists';
  else if(playerStore.mediaMode!=='VIDEO'){ feedback.clear(); invalidateSearch(); }
});
watch([keyword,platform,()=>playerStore.clientGeneration,()=>playerStore.modeEpoch],feedback.clear,{flush:'sync'});
watch(() => [playerStore.mediaMode, playerStore.modeEpoch, playerStore.clientGeneration], () => {
  feedback.clear();
  mode.value = 'search'; pendingIds.value = new Set(); selectedSongIds.value = new Set();
  playlistFilterInput.value = ''; playlistFilterKeyword.value = ''; mobileView.value = playerStore.mediaMode === 'VIDEO' ? 'songs' : 'playlists';
});
</script>

<style scoped>
.search-header{padding-bottom:0}
.search-feedback-help{width:24px;height:24px;display:inline-flex;align-items:center;justify-content:center;flex:none}.search-feedback-help svg{width:14px;height:14px}.search-feedback-help:focus-visible{outline:1px solid rgb(var(--accent));outline-offset:-2px}
 .search-feedback-slot{height:25px;flex:none;padding:0 16px;display:flex;align-items:center;position:relative;background:rgb(var(--surface));border-bottom:1px solid rgb(var(--medical-200));font-size:12px;line-height:18px;color:rgb(var(--medical-500))}
.search-feedback-label{white-space:pre}.search-feedback-char{display:inline-block}.is-pending .search-feedback-char{animation:search-feedback-hop 1.6s ease-in-out infinite;animation-delay:calc(var(--char-index)*55ms)}@keyframes search-feedback-hop{0%,16%,100%{transform:translateY(0)}7%{transform:translateY(-3px)}}@media(prefers-reduced-motion:reduce){.is-pending .search-feedback-char{animation:none}}
.search-feedback-text{display:flex;align-items:center;gap:6px}.search-feedback-text>svg{width:12px;height:12px}.search-feedback-text button{color:rgb(var(--accent));font-family:inherit}.search-feedback-failed{color:rgb(var(--medical-600))}.search-feedback-details{position:absolute;left:16px;right:16px;top:100%;z-index:65;padding:12px;background:rgb(var(--surface));color:rgb(var(--medical-800));border:1px solid rgb(var(--medical-200));box-shadow:0 6px 18px rgb(var(--overlay)/.12);overflow-wrap:anywhere}
.playlist-import-actions { width: 116px; }
.import-all { width: 84px; }
.import-selected { width: 24px; }
.import-selected span { opacity: 0; }
.playlist-import-actions:has(.import-selected:hover, .import-selected:focus-visible) .import-all { width: 24px; }
.playlist-import-actions:has(.import-selected:hover, .import-selected:focus-visible) .import-all span { opacity: 0; }
.playlist-import-actions:has(.import-selected:hover, .import-selected:focus-visible) .import-selected { width: 84px; }
.playlist-import-actions:has(.import-selected:hover, .import-selected:focus-visible) .import-selected span { opacity: 1; }
</style>
