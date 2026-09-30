<template>
  <div class="hidden">
    <audio
        :key="spectrumMode ? 'spectrum' : 'plain'"
        ref="audioRef"
        :crossorigin="spectrumMode ? 'anonymous' : undefined"
        :src="audioSrc"
        @error="handleMediaError"
        @waiting="onWaiting"
        @playing="onPlaying"
        @canplay="onCanPlay"
        @seeked="onCanPlay"
        @loadedmetadata="restorePosition"
        @ended="handleEnded"
        referrerpolicy="no-referrer"
    ></audio>
  </div>
</template>

<script setup>
import { mediaRoomUrl } from '../services/roomSession';
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useAudio } from '../composables/useAudio';
import { audioSpectrum } from '../logic/AudioSpectrum';
import { useToast } from '../composables/useToast';

const player = usePlayerStore();
const ui = useUiStore();
const audioRef = ref(null);
const { info } = useToast();

const {
  localProgress,
  isBuffering,
  isErrorState,
  handleError,
  checkAutoPlay,
  handleEnded,
  onWaiting,
  onPlaying: markPlaying
} = useAudio(audioRef, player);

// 记住最后一首有效 URL：服务器拉取下一首（nowPlaying=null）的间隙不硬断
const lastGoodUrl = ref('');
watch(() => player.nowPlaying?.music?.url, (url) => {
  if (url) lastGoodUrl.value = url;
});

const audioSrc = computed(() => {
  const current = player.nowPlaying?.music?.url;
  if (current) return mediaRoomUrl(current);
  // 服务器仍在加载下一首：继续播上一首，避免间隙中断
  if (player.isLoading && lastGoodUrl.value) return mediaRoomUrl(lastGoodUrl.value);
  // 服务器空闲：清空 src，音频自然停止
  return '';
});

const supportsSpectrum = !!(window.AudioContext || window.webkitAudioContext);
const blockedUrl = ref(null);
const spectrumMode = computed(() => ui.visualizationEnabled && supportsSpectrum && blockedUrl.value !== audioSrc.value);
let restoreUrl = null;
let warned = false;

// Web Audio 需要以 CORS 模式加载。切换时替换 audio，避免同一元素重复连接或静音。
watch(spectrumMode, () => {
  if (audioRef.value?.currentSrc) restoreUrl = audioSrc.value;
  audioRef.value?.pause();
}, { flush: 'sync' });

const updateSpectrumActivity = () => {
  audioSpectrum.setActive(spectrumMode.value && !ui.isLiteMode && !player.isPaused && !document.hidden);
};
watch(() => [ui.isLiteMode, player.isPaused], updateSpectrumActivity);
watch(audioRef, (audio, previous) => {
  previous?.pause();
  audioSpectrum.dispose();
  if (!audio) return;
  audio.volume = ui.audioVolume;
  if (spectrumMode.value) {
    try {
      audioSpectrum.attach(audio);
      updateSpectrumActivity();
    } catch {
      fallBackToPlainAudio();
    }
  }
}, { flush: 'post' });

const fallBackToPlainAudio = () => {
  blockedUrl.value = audioSrc.value;
  if (!warned) {
    info('当前音源暂不支持可视化，继续正常播放');
    warned = true;
  }
};
const handleMediaError = (event) => {
  if (event.target !== audioRef.value) return;
  if (spectrumMode.value && audioSrc.value) fallBackToPlainAudio();
  else handleError();
};
const restorePosition = () => {
  if (restoreUrl === audioSrc.value && player.nowPlaying && audioRef.value) {
    const audio = audioRef.value;
    const position = player.getCurrentProgress() / 1000;
    audio.currentTime = Math.max(0, Math.min(position, Number.isFinite(audio.duration) ? audio.duration : position));
  }
  restoreUrl = null;
};
const onPlaying = () => {
  audioSpectrum.resume();
  markPlaying();
};

// 同步状态到 playerStore
watch(localProgress, (val) => {
  player.localProgress = val;
});
watch(isBuffering, (val) => {
  player.isBuffering = val;
});
watch(isErrorState, (val) => {
  player.isErrorState = val;
});

// 监听音量
watch(() => ui.audioVolume, (newVol) => {
  if (audioRef.value) {
    audioRef.value.volume = newVol;
  }
}, { immediate: true });

const onCanPlay = (event) => {
  if (event.target !== audioRef.value) return;
  player.isBuffering = false;
  audioSpectrum.resume();
  checkAutoPlay();
};

onMounted(() => {
  document.addEventListener('visibilitychange', updateSpectrumActivity);
  document.addEventListener('pointerdown', audioResume);
  document.addEventListener('keydown', audioResume);
  if (audioRef.value) {
    audioRef.value.volume = ui.audioVolume;
  }
});

const audioResume = () => audioSpectrum.resume();
onUnmounted(() => {
  document.removeEventListener('visibilitychange', updateSpectrumActivity);
  document.removeEventListener('pointerdown', audioResume);
  document.removeEventListener('keydown', audioResume);
  audioSpectrum.dispose();
});
</script>
