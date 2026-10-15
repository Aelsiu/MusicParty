<template>
  <div class="h-[calc(8rem+env(safe-area-inset-bottom))] md:h-24 shrink-0 pt-2 pb-[calc(0.5rem+env(safe-area-inset-bottom))] md:py-0 bg-surface border-t border-medical-200 flex items-center px-4 md:px-8 relative z-50 shadow-lg">
    <!-- 封面 -->
    <div
        id="tutorial-source"
        data-guide="source"
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
          <OverflowText as="h2" class="text-lg font-bold  leading-tight transition-colors duration-300"
              :class="!player.connected ? 'text-orange-600 animate-pulse' : 'text-medical-900'"
           :text="String(!player.connected
                  ? '!CONNECTION LOST!'
                  : (nowPlaying ? nowPlaying.music.name : 'WAITING FOR SIGNAL...'))" />

          <!-- 副标题显示逻辑与样式 -->
          <OverflowText as="p" class="text-xs font-sans  transition-colors duration-300"
             :class="!player.connected ? 'text-orange-500 animate-pulse' : 'text-medical-800/60'"
           :text="String(!player.connected
                  ? 'RECONNECT SERVER...'
                  : (nowPlaying ? nowPlaying.music.artists.join(' / ') : 'SYSTEM STANDBY'))" />
        </div>

        <!-- 时间显示 -->
        <div class="hidden md:block min-w-[19ch] whitespace-nowrap tabular-nums text-right font-mono text-xs text-medical-800/60 flex-shrink-0 ml-2">
           <span v-if="player.isLoading" class="text-accent animate-pulse">SYNCING SERVER...</span>
           <span v-if="player.isBuffering" class="animate-pulse text-accent">BUFFERING...</span>
           <span v-else>{{ formatDuration(controlProgress) }} / {{ formatDuration(nowPlaying?.music.duration || 0) }}</span>
        </div>
      </div>

      <!-- 进度条 -->
      <div class="h-5 w-full relative flex items-center outline-none focus-visible:ring-1 focus-visible:ring-accent"
           data-guide="progress"
           role="slider" aria-label="播放进度" :aria-disabled="!player.canSeek || coolingDown" :tabindex="player.canSeek&&!coolingDown?0:-1"
           :aria-valuemin="0" :aria-valuemax="nowPlaying?.music.duration || 0" :aria-valuenow="Math.round(controlProgress)"
           :class="player.canSeek&&!coolingDown?'cursor-pointer':'cursor-not-allowed'"
           @pointerdown="rememberPlayback" @click="seekFromPointer" @keydown="seekFromKeyboard">
        <div class="h-1 bg-medical-200 w-full relative">
        <span v-for="(range, index) in bufferedRanges" :key="`buffer-${index}`" aria-hidden="true"
              class="video-buffer-segment absolute inset-y-0 bg-medical-400/60 pointer-events-none"
              :style="{left:range[0]/nowPlaying.music.duration*100+'%',width:(range[1]-range[0])/nowPlaying.music.duration*100+'%'}"></span>
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
        <button v-if="player.mediaMode === 'VIDEO'" data-guide="volume" ref="volumeButton" @click="toggleMobileVolume" aria-label="本机音量"
                :aria-expanded="mobileVolumeOpen" aria-controls="musicparty-room-volume"
                class="p-2 bg-medical-50 border border-medical-200 rounded-sm text-medical-500">
          <Volume2 class="w-4 h-4" />
        </button>
        <button v-if="player.mediaMode === 'MUSIC'" id="tutorial-download-mobile" data-guide="download" @click="downloadCurrentMusic" class="p-2 bg-medical-50 border border-medical-200 rounded-sm text-medical-500 active:bg-medical-200">
          <Download class="w-4 h-4" />
        </button>
        <button
            id="tutorial-random-mobile"
            data-guide="play-mode"
            @click="player.cyclePlayMode"
            :disabled="!player.synchronized || player.isPlayModeLocked"
            class="p-2 border rounded-sm disabled:opacity-50 transition-colors bg-medical-50 border-medical-200 text-medical-500"
        >
          <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-4 h-4" />
          <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-4 h-4" />
          <Repeat1 v-else class="w-4 h-4" />
        </button>
         <button id="tutorial-pause-mobile" data-guide="pause" @click="player.togglePause" :aria-label="player.intentPaused ? '恢复房间播放' : '暂停房间播放'" :disabled="!player.synchronized || (player.isPauseLocked && !player.intentPaused)" class="p-2 bg-medical-100 rounded-sm disabled:opacity-50">
             <Lock v-if="player.isPauseLocked && !player.intentPaused" class="w-4 h-4 text-medical-400" />
             <template v-else>
                 <Play v-if="player.intentPaused" class="w-4 h-4" />
                 <Pause v-else class="w-4 h-4" />
             </template>
         </button>
         <button 
             data-guide="next"
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
        <button v-if="player.mediaMode === 'MUSIC'" id="tutorial-download" data-guide="download" @click="downloadCurrentMusic" class="text-medical-400 hover:text-accent transition-colors" title="Download">
          <Download class="w-5 h-5" />
        </button>

        <button id="tutorial-random" data-guide="play-mode" @click="player.cyclePlayMode" :disabled="!player.synchronized || player.isPlayModeLocked" :class="['text-medical-400', player.isPlayModeLocked ? 'opacity-50 cursor-not-allowed' : '']" :title="modeTitle">
            <ListOrdered v-if="player.playMode === 'SEQUENTIAL'" class="w-5 h-5" />
            <Shuffle v-else-if="player.playMode === 'SHUFFLE'" class="w-5 h-5" />
            <Repeat1 v-else class="w-5 h-5" />
        </button>
        
        <button 
            id="tutorial-pause"
            data-guide="pause"
            @click="player.togglePause" 
            :aria-label="player.intentPaused ? '恢复房间播放' : '暂停房间播放'"
            :disabled="!player.synchronized || (player.isPauseLocked && !player.intentPaused)"
            class="w-10 h-10 bg-strong text-white flex items-center justify-center hover:bg-accent transition-colors chamfer-tl disabled:opacity-50 disabled:hover:bg-strong disabled:cursor-not-allowed"
        >
            <Lock v-if="player.isPauseLocked && !player.intentPaused" class="w-4 h-4 text-white" />
            <template v-else>
                <Play v-if="player.intentPaused" class="w-4 h-4 fill-current" />
                <Pause v-else class="w-4 h-4 fill-current" />
            </template>
        </button>

        <button 
            data-guide="next"
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

    </div>
    <!-- One volume editor survives responsive layout and Teleport migrations. -->
    <div id="musicparty-room-volume" ref="volumePanel" :class="mobileVolumeOpen ? 'absolute right-4 bottom-full mb-2 flex bg-surface border border-medical-200 shadow-lg p-3' : 'hidden'"
         class="md:static md:flex md:mb-0 md:bg-transparent md:border-0 md:shadow-none md:p-0 items-center gap-2"
         @pointerdown.capture="volumePointerDown" @keydown.esc.stop.prevent="closeMobileVolume(true)">
      <VolumeControl class="player-volume" compact />
      <button v-show="mobileVolumeOpen" @click="closeMobileVolume(true)" class="md:hidden p-1 text-medical-500" aria-label="关闭音量面板"><X class="w-4 h-4" /></button>
    </div>
  </div>
</template>

<script setup>
import OverflowText from './OverflowText.vue';
import { computed, ref, watch, inject, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { formatDuration } from '../utils/format';
import { Download, ListOrdered, Repeat1, Shuffle, SkipForward, Play, Pause, ExternalLink, Zap, Lock, Volume2, X } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import VolumeControl from './VolumeControl.vue';
import { useToast } from '../composables/useToast';
import { videoSourcePage, videoBvid } from '../utils/playbackProtocol';
import { videoPresentationKey } from '../composables/videoPresentationContext';
import { roomSession } from '../services/roomSession';

const player = usePlayerStore();
const { info, error } = useToast();
const presentation = inject(videoPresentationKey, null), mobileVolumeOpen = ref(false), volumePanel = ref(null), volumeButton = ref(null);
let releaseVolumeMenu, releaseVolumeDrag;
const closeMobileVolume = (restore = false) => {
    if (!mobileVolumeOpen.value) return false;
    mobileVolumeOpen.value = false; releaseVolumeMenu?.(); releaseVolumeMenu = null;
    if (restore) volumeButton.value?.focus();
    return true;
};
const toggleMobileVolume = async () => {
    if (closeMobileVolume(true)) return;
    if (player.mediaMode !== 'VIDEO') return;
    mobileVolumeOpen.value = true; releaseVolumeMenu = presentation?.acquireInteractionLock('menu');
    await nextTick(); if (mobileVolumeOpen.value) volumePanel.value?.querySelector('input')?.focus();
};
const volumePointerDown = event => {
    if (event.target?.type !== 'range' || releaseVolumeDrag) return;
    releaseVolumeDrag = presentation?.acquireInteractionLock('dragging');
};
const releaseDrag = () => { releaseVolumeDrag?.(); releaseVolumeDrag = null; };
const unregisterVolumeMenu = presentation?.registerMenuCloser(() => closeMobileVolume(true));
watch(() => [roomSession.generation, player.mediaMode, presentation?.registration.value], () => { closeMobileVolume(); releaseDrag(); });
onMounted(() => { document.addEventListener('pointerup', releaseDrag); document.addEventListener('pointercancel', releaseDrag); });
onBeforeUnmount(() => { closeMobileVolume(); releaseDrag(); unregisterVolumeMenu?.(); document.removeEventListener('pointerup', releaseDrag); document.removeEventListener('pointercancel', releaseDrag); });

const nowPlaying = computed(() => player.nowPlaying);
const likeMarkers = computed(() => player.mediaMode === 'MUSIC' ? nowPlaying.value?.likeMarkers || [] : []);
const clock = ref(Date.now());
const seekClock = setInterval(() => { clock.value=Date.now(); },100);
onBeforeUnmount(() => clearInterval(seekClock));
const coolingDown = computed(() => clock.value < player.seekDeadline);
const controlProgress = computed(() => {
    // VIDEO's room controls follow the authoritative room clock while the local
    // picture/danmaku may be frozen. Reuse the existing seekClock for display.
    if (player.mediaMode !== 'VIDEO') return player.localProgress;
    void clock.value;
    return player.videoWait?.frozenPosition ?? player.getCurrentProgress();
});
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
    if (event.key==='ArrowLeft') target=controlProgress.value-5000;
    else if (event.key==='ArrowRight') target=controlProgress.value+5000;
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
    const elapsedSec = Math.floor(controlProgress.value / 1000);
    return Math.max(0, player.voteSkipWaitTime - elapsedSec);
});

const isSkipDisabled = computed(() => {
    if (!player.synchronized) return true;
    if (player.isSkipLocked) return true;
    if (player.mediaMode === 'VIDEO' && player.videoFeatures.interactionState && player.isVoteSkipEnabled && nowPlaying.value?.enqueuedById !== useUserStore().userToken) return !player.interaction?.canVote;
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
    if (player.isSkipLocked) return '切换功能已被锁定';
    if (player.mediaMode === 'VIDEO' && player.videoFeatures.interactionState && player.isVoteSkipEnabled && canVote.value) {
        if (!player.interaction) return '正在核对投票资格';
        if (player.interaction.reason === 'WAITING') return `播放至 ${player.voteSkipWaitTime} 秒后可投票`;
        return `${player.interaction.hasVoted ? '撤销投票' : '投票切视频'} (${player.currentVotes}/${player.eligibleUsers})`;
    }
    if (player.isVoteSkipEnabled) {
        if (!canVote.value) return '强制切歌';
        if (waitTimeLeft.value > 0) return `${waitTimeLeft.value}s 后可投票`;
        return `投票切歌 (${player.currentVotes}/${player.eligibleUsers})`;
    }
    return 'Next';
});

// 计算进度百分比
const bufferedRanges = computed(() => player.mediaMode === 'VIDEO' && player.synchronized && nowPlaying.value?.music.duration > 0
    ? player.videoBufferedRanges || [] : []);
const progressPercent = computed(() => {
  if (!nowPlaying.value || nowPlaying.value.music.duration === 0) return 0;
  return Math.min(100, (controlProgress.value / nowPlaying.value.music.duration) * 100);
});

// --- 下载逻辑 ---
const downloadCurrentMusic = async () => {
  if (!nowPlaying.value || player.mediaMode !== 'MUSIC') return;
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
  let url = platform === 'netease' ? `https://music.163.com/#/song?id=${id}` : videoSourcePage(id,player.nowPlaying?.music?.videoSelection);
  if (url) window.open(url, '_blank');
};

</script>

<style scoped>.player-volume{margin-inline-start:16px;min-width:0}@media(max-width:767px){.player-volume{margin-inline-start:12px}}</style>
