<template>
  <div class="h-24 bg-surface border-t border-medical-200 flex items-center px-4 md:px-8 relative z-50 shadow-lg">
    <!-- 封面 -->
    <div
        id="tutorial-source"
        @click="openSourcePage"
        class="w-16 h-16 md:w-20 md:h-20 -mt-6 md:mt-0 shadow-lg border-2 border-white chamfer-br flex-shrink-0 relative z-10 bg-strong cursor-pointer group overflow-hidden"
        title="Open Source Page"
    >
      <CoverImage :src="nowPlaying?.music.coverUrl" class="w-full h-full transition-transform duration-300 group-hover:scale-110 group-hover:opacity-50" />

      <!-- 悬浮时的遮罩和图标 -->
      <div class="absolute inset-0 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity duration-300 bg-black/40">
        <ExternalLink class="w-6 h-6 text-white" />
      </div>
    </div>

    <!-- 中间：信息与进度 -->
    <div class="flex-1 ml-4 mr-4 md:mr-8 flex flex-col justify-center min-w-0">
      <div class="flex justify-between items-end mb-1">
        <div class="overflow-hidden w-full">
          <!-- 标题显示逻辑与样式 -->
          <h2 class="text-lg font-bold truncate leading-tight transition-colors duration-300"
              :class="!player.connected ? 'text-orange-600 animate-pulse' : 'text-medical-900'"
          >
            {{
              !player.connected
                  ? '!CONNECTION LOST!'
                  : (nowPlaying ? nowPlaying.music.name : 'WAITING FOR SIGNAL...')
            }}
          </h2>

          <!-- 副标题显示逻辑与样式 -->
          <p class="text-xs font-sans truncate transition-colors duration-300"
             :class="!player.connected ? 'text-orange-500 animate-pulse' : 'text-medical-800/60'"
          >
            {{
              !player.connected
                  ? 'RECONNECT SERVER...'
                  : (nowPlaying ? nowPlaying.music.artists.join(' / ') : 'SYSTEM STANDBY')
            }}
          </p>
        </div>

        <!-- 时间显示 -->
        <div class="hidden md:block font-mono text-xs text-medical-800/60 flex-shrink-0 ml-2">
           <span v-if="player.isLoading" class="text-accent animate-pulse">SYNCING SERVER...</span>
           <span v-if="player.isBuffering" class="animate-pulse text-accent">BUFFERING...</span>
           <span v-else>{{ formatDuration(player.localProgress) }} / {{ formatDuration(nowPlaying?.music.duration || 0) }}</span>
        </div>
      </div>

      <!-- 进度条 -->
      <div class="h-5 w-full relative flex items-center outline-none focus-visible:ring-1 focus-visible:ring-accent"
           role="slider" aria-label="播放进度" :aria-disabled="!player.canSeek || coolingDown" :tabindex="player.canSeek&&!coolingDown?0:-1"
           :aria-valuemin="0" :aria-valuemax="nowPlaying?.music.duration || 0" :aria-valuenow="Math.round(player.localProgress)"
           :class="player.canSeek&&!coolingDown?'cursor-pointer':'cursor-not-allowed'"
           @pointerdown="rememberPlayback" @click="seekFromPointer" @keydown="seekFromKeyboard">
        <div class="h-1 bg-medical-200 w-full relative">
        <span v-for="marker in player.chorusMarkers" :key="`chorus-${marker}`" class="absolute z-30 -translate-x-1/2 -top-1.5 text-accent"
              :style="{left:(marker/(nowPlaying?.music.duration||1))*100+'%'}" :aria-label="`副歌 ${formatDuration(marker)}`">
          <span class="block w-0 h-0 border-x-[4px] border-x-transparent border-t-[5px] border-t-current"></span>
        </span>

        <div
            v-for="(marker, index) in likeMarkers"
            :key="index"
            class="absolute z-20 transition-all duration-300 transform -translate-y-1/2 top-1/2"
            :style="{ left: (marker / (nowPlaying?.music.duration || 1)) * 100 + '%' }"
        >
          <Zap
              class="w-3 h-3 drop-shadow-md "
              :class="'text-accent fill-accent'"
          />
        </div>

        <div
            class="h-full transition-all duration-300 ease-linear relative"
            :class="player.isErrorState ? 'bg-red-500' : 'bg-accent'"
            :style="{ width: progressPercent + '%' }"
        >
          <div
              v-if="!player.isErrorState"
              class="absolute right-0 top-1/2 -translate-y-1/2 w-2 h-2 rotate-45 transition-all duration-300 bg-accent"
          ></div>
        </div>
        </div>
      </div>
      
      <!-- 移动端简易控制 -->
      <div class="flex md:hidden justify-end gap-3 mt-2">
        <button id="tutorial-download-mobile" @click="downloadCurrentMusic" class="p-2 bg-medical-50 border border-medical-200 rounded-sm text-medical-500 active:bg-medical-200">
          <Download class="w-4 h-4" />
        </button>
        <button
            id="tutorial-random-mobile"
            @click="player.cyclePlayMode"
            :disabled="!player.connected || player.isPlayModeLocked"
            class="p-2 border rounded-sm disabled:opacity-50 transition-colors bg-medical-50 border-medical-200 text-medical-500"
        >
          <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-4 h-4" />
          <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-4 h-4" />
          <Repeat1 v-else class="w-4 h-4" />
        </button>
         <button id="tutorial-pause-mobile" @click="player.togglePause" :disabled="player.isPauseLocked && !player.isPaused" class="p-2 bg-medical-100 rounded-sm disabled:opacity-50">
             <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4 text-medical-400" />
             <template v-else>
                 <Play v-if="player.isPaused" class="w-4 h-4" />
                 <Pause v-else class="w-4 h-4" />
             </template>
         </button>
         <button 
             @click="player.playNext" 
             :disabled="isSkipDisabled" 
             class="p-2 bg-medical-100 rounded-sm disabled:opacity-50 relative"
         >
             <SkipForward class="w-4 h-4" />
             <!-- Badge -->
             <div v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0" class="absolute -top-1 -right-1 bg-accent text-white text-[8px] font-bold px-1 min-w-[12px] h-3 flex items-center justify-center rounded-sm shadow-sm">
                {{ player.currentVotes }}
             </div>
         </button>
      </div>
    </div>

    <!-- PC端：右侧控制区 -->
    <div class="hidden md:flex items-center gap-6 flex-shrink-0">
      
      <!-- 播放控制 -->
      <div class="flex items-center gap-4 border-r border-medical-200 pr-6">
        <!-- 新增：下载按钮 (放在 Shuffle 旁边或者 Next 后面) -->
        <button id="tutorial-download" @click="downloadCurrentMusic" class="text-medical-400 hover:text-accent transition-colors" title="Download">
          <Download class="w-5 h-5" />
        </button>

        <button id="tutorial-random" @click="player.cyclePlayMode" :disabled="player.isPlayModeLocked" :class="['text-medical-400', player.isPlayModeLocked ? 'opacity-50 cursor-not-allowed' : '']" :title="modeTitle">
            <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-5 h-5" />
            <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-5 h-5" />
            <Repeat1 v-else class="w-5 h-5" />
        </button>
        
        <button 
            id="tutorial-pause"
            @click="player.togglePause" 
            :disabled="player.isPauseLocked && !player.isPaused"
            class="w-10 h-10 bg-strong text-white flex items-center justify-center hover:bg-accent transition-colors chamfer-tl disabled:opacity-50 disabled:hover:bg-strong disabled:cursor-not-allowed"
        >
            <Lock v-if="player.isPauseLocked && !player.isPaused" class="w-4 h-4 text-white" />
            <template v-else>
                <Play v-if="player.isPaused" class="w-4 h-4 fill-current" />
                <Pause v-else class="w-4 h-4 fill-current" />
            </template>
        </button>

        <button 
            @click="player.playNext" 
            :disabled="isSkipDisabled" 
            class="text-medical-800 hover:text-accent transition-colors disabled:opacity-50 disabled:cursor-not-allowed relative group/skip" 
            :title="skipBtnTitle"
        >
            <SkipForward class="w-6 h-6 fill-current" />
            <!-- Badge -->
            <div v-if="player.isVoteSkipEnabled && canVote && player.currentVotes > 0" class="absolute -top-1 -right-1 bg-accent text-white text-[10px] font-bold px-1 min-w-[14px] h-3.5 flex items-center justify-center rounded-sm shadow-sm">
                {{ player.currentVotes }}
            </div>
            <!-- Wait Timer Hint -->
            <div v-if="player.isVoteSkipEnabled && canVote && waitTimeLeft > 0" class="absolute -bottom-6 left-1/2 -translate-x-1/2 bg-strong text-white text-[8px] px-1 py-0.5 rounded-sm opacity-0 group-hover/skip:opacity-100 transition-opacity whitespace-nowrap">
                ⓘ {{ waitTimeLeft }}s 后可投票
            </div>
        </button>
      </div>

      <!-- 音量控制 -->
      <VolumeControl compact />
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { formatDuration } from '../utils/format';
import { Download, ListOrdered, Repeat1, Shuffle, SkipForward, Play, Pause, ExternalLink, Zap, Lock } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import VolumeControl from './VolumeControl.vue';
import { useToast } from '../composables/useToast';

const player = usePlayerStore();
const { info, error } = useToast();

const nowPlaying = computed(() => player.nowPlaying);
const likeMarkers = computed(() => nowPlaying.value?.likeMarkers || []);
const clock = ref(Date.now());
const seekClock = setInterval(() => { clock.value=Date.now(); },100);
onBeforeUnmount(() => clearInterval(seekClock));
const coolingDown = computed(() => clock.value < player.seekDeadline);
let pointedPlayback;
const rememberPlayback = () => { pointedPlayback=nowPlaying.value?.playbackId; };
const seekFromPointer = event => {
    if (event.detail===0 || pointedPlayback!==nowPlaying.value?.playbackId || !player.canSeek || coolingDown.value) return;
    const rect=event.currentTarget.getBoundingClientRect();
    player.seek((event.clientX-rect.left)/rect.width*nowPlaying.value.music.duration);
};
const seekFromKeyboard = event => {
    if (!player.canSeek || coolingDown.value) return;
    let target;
    if (event.key==='ArrowLeft') target=player.localProgress-5000;
    else if (event.key==='ArrowRight') target=player.localProgress+5000;
    else if (event.key==='Home') target=0;
    else if (event.key==='End') target=nowPlaying.value.music.duration-1;
    else return;
    event.preventDefault();player.seek(target);
};

const modeTitle = computed(() => {
    switch (player.playMode) {
        case 'SEQUENTIAL': return '顺序播放';
        case 'SHUFFLE': return '随机播放';
        case 'REPEAT_ONE': return '单曲循环';
        default: return '';
    }
});

// 投票切歌相关计算
const waitTimeLeft = computed(() => {
    if (!player.isVoteSkipEnabled || !nowPlaying.value) return 0;
    const elapsedSec = Math.floor(player.localProgress / 1000);
    return Math.max(0, player.voteSkipWaitTime - elapsedSec);
});

const isSkipDisabled = computed(() => {
    if (player.isSkipLocked) return true;
    if (player.isVoteSkipEnabled) {
        // 如果是点歌者（不可投票者），允许直接切歌，不受 15s 限制
        if (!canVote.value) return false;
        // 投票模式下：只有在等待时间结束后才能点击
        return waitTimeLeft.value > 0;
    }
    return false;
});

const canVote = computed(() => {
    // 只有非点歌者能投票
    const userStore = useUserStore();
    return nowPlaying.value && nowPlaying.value.enqueuedById !== userStore.userToken;
});

const skipBtnTitle = computed(() => {
    if (player.isSkipLocked) return '切歌功能已被锁定';
    if (player.isVoteSkipEnabled) {
        if (!canVote.value) return '强制切歌';
        if (waitTimeLeft.value > 0) return `${waitTimeLeft.value}s 后可投票`;
        return `投票切歌 (${player.currentVotes}/${player.eligibleUsers})`;
    }
    return 'Next';
});

// 计算进度百分比
const progressPercent = computed(() => {
  if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
  return Math.min(100, (player.localProgress / nowPlaying.value.music.duration) * 100);
});

// --- 下载逻辑 ---
const downloadCurrentMusic = async () => {
  if (!nowPlaying.value) return;
  const music = nowPlaying.value.music;
  info(`Starting download: ${music.name}...`);
  try {
    const response = await fetch(music.url);
    if (!response.ok) throw new Error('Network error');
    const blob = await response.blob();
    const blobUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = `${music.name} - ${music.artists[0]}.mp3`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(blobUrl);
  } catch (e) {
    window.open(music.url, '_blank');
    error('Blob download failed, opening new tab.');
  }
};

// 跳转源页面
const openSourcePage = () => {
  if (!nowPlaying.value) return;
  const { platform, id } = nowPlaying.value.music;
  let url = platform === 'netease' ? `https://music.163.com/#/song?id=${id}` : `https://www.bilibili.com/video/${id}`;
  if (url) window.open(url, '_blank');
};

</script>
