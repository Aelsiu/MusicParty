<template>
  <div class="relative w-full aspect-video bg-black flex items-center justify-center overflow-hidden">
    <video ref="videoRef" class="w-full h-full object-contain" playsinline preload="auto"
           disablepictureinpicture @loadedmetadata="onReady" @canplay="onReady" @playing="onPlaying"
           @waiting="onWaiting" @stalled="onStalled" @timeupdate="onTimeUpdate" @seeked="onReady"
           @pause="onPause" @ended="onEnded" @error="onError"
           @ratechange="videoRef && videoRef.playbackRate !== player.playbackRate && (videoRef.playbackRate = player.playbackRate)" />
    <div v-if="!player.nowPlaying" class="absolute inset-0 flex items-center justify-center text-white/70 text-sm p-6 text-center">
      {{ player.isLoading ? '房间正在解析视频源…' : '点播 B 站视频，然后手动播放' }}
    </div>
    <button v-if="needsInteraction && !player.isPaused" @click="playLocal" class="absolute bg-accent text-white font-bold px-6 py-3 shadow-lg">点击开始观看 / 恢复观看</button>
    <div v-if="player.policyRecoveryRequired" class="absolute bottom-0 inset-x-0 bg-black/90 text-white text-xs p-4 flex items-center gap-3"><span role="alert">系统分辨率上限下没有可用视频源。上限调整后须手动恢复。</span><button @click="player.recoverVideoSource" :disabled="!player.synchronized || player.isLoading" class="shrink-0 border border-white/50 px-3 py-2 disabled:opacity-40">重新获取视频源</button></div>
    <div v-else-if="errorMessage" class="absolute bottom-0 inset-x-0 bg-black/80 text-white text-xs px-4 py-3 flex items-center justify-between gap-3">
      <span role="alert">{{ errorMessage }}</span><button @click="manualRefresh" class="shrink-0 border border-white/50 px-3 py-2">刷新源</button>
    </div>
  </div>
</template>
<script setup>
import { ref } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useVideo } from '../composables/useVideo';
const player = usePlayerStore(), ui = useUiStore(), videoRef = ref(null);
const { needsInteraction, errorMessage, playLocal, manualRefresh, onReady, onWaiting, onStalled, onTimeUpdate, onPlaying, onPause, onEnded, onError } = useVideo(videoRef, player, ui);
</script>
