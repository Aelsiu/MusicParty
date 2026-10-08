<template>
  <section ref="container" class="video-console flex-1 min-h-0 flex flex-col justify-center overflow-y-auto p-3 md:p-6"
           :class="expanded ? 'fixed inset-0 z-[80] bg-medical-50' : ''">
    <div class="w-full max-w-5xl mx-auto border border-medical-200 bg-surface shadow-lg">
      <div class="px-4 py-3 flex items-center justify-between gap-3 border-b border-medical-200">
        <span class="text-xs font-bold text-accent">一起看视频<span v-if="player.nowPlaying?.videoDescriptor"> · P{{ player.nowPlaying.videoDescriptor.page }}</span></span>
        <div class="flex gap-3 text-xs">
          <button @click="refresh" :disabled="!player.synchronized || !player.nowPlaying || retryRemaining > 0" class="text-accent disabled:opacity-40">{{ retryRemaining > 0 ? `${retryRemaining}s 后刷新` : '刷新视频源' }}</button>
          <button @click="toggleExpanded" class="text-medical-500">{{ expanded ? '收起' : '展开' }}</button>
        </div>
      </div>
      <VideoEngine />
      <div class="flex flex-wrap items-center gap-3 px-4 pt-3 text-xs text-medical-600">
        <label class="flex items-center gap-2">房间倍速
          <select aria-label="房间视频倍速" :value="player.playbackRate" @change="player.setVideoRate(Number($event.target.value))" :disabled="!roomSession.ownerAccess || !player.synchronized"
                  class="bg-medical-50 border border-medical-200 px-2 py-1 disabled:opacity-50">
            <option v-for="rate in rates" :key="rate" :value="rate">{{ rate }}×</option>
          </select>
        </label>
        <label v-if="pages.length > 1" class="flex items-center gap-2 min-w-0">当前分 P
          <select aria-label="切换当前视频分 P" :value="player.nowPlaying.videoDescriptor.page" @change="player.switchVideoPage(Number($event.target.value))"
                  :disabled="!roomSession.ownerAccess || !player.synchronized || player.isSkipLocked" class="max-w-[240px] min-w-0 bg-medical-50 border border-medical-200 px-2 py-1 disabled:opacity-50">
            <option v-for="page in pages" :key="page.page" :value="page.page" :disabled="!page.withinDurationLimit">P{{ page.page }} · {{ page.title }}{{ page.withinDurationLimit ? '' : '（超出时长上限）' }}</option>
          </select>
        </label>
        <span v-if="pages.length > 1" class="text-medical-400">切 P 从 0 起播；管理者可切换</span>
      </div>
      <div class="px-4 py-3 text-xs text-medical-500 leading-relaxed" role="status">
        <span v-if="!player.synchronized">正在同步房间状态…</span>
        <span v-else-if="!player.config.bilibiliCookieConfigured">房间尚未配置 B 站 Cookie，请管理者在管理台更新。</span>
        <span v-else-if="player.videoWait">全员等待缓冲 · {{ player.videoWait.readyCount }} / {{ player.videoWait.participantCount }} 已就绪 · 最多再等 {{ waitRemaining }} 秒。播放按钮可手动暂停本轮等待。</span>
        <span v-else-if="player.sourceStatus === 'RESOLVING'">房间正在获取视频源，当前进度会保留。</span>
        <span v-else-if="player.sourceStatus === 'ERROR'">视频源获取失败，当前项已保留。请查看通知、更新 Cookie 或刷新源。</span>
        <span v-else-if="player.intentPaused">房间已暂停。播放按钮控制全房间，本端“开始观看”只允许本端播放。</span>
        <span v-else-if="player.isBuffering">本端缓冲中；持续 2 秒后会请求全员等待。</span>
        <span v-else>各端直接连接 B 站视频源 · {{ player.clockReady ? '时间探测已就绪' : '时间探测尚未就绪' }}</span>
      </div>
    </div>
    <div v-if="expanded" class="shrink-0 w-full max-w-5xl mx-auto"><PlayerControl /></div>
  </section>
</template>
<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import VideoEngine from './VideoEngine.vue';
import PlayerControl from './PlayerControl.vue';
import { usePlayerStore } from '../stores/player';
import { roomSession } from '../services/roomSession';
const player = usePlayerStore(), container = ref(null), expanded = ref(false), now = ref(Date.now());
const rates = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
const pages = computed(() => player.nowPlaying?.videoDescriptor?.pages || []);
const waitRemaining = computed(() => Math.max(0, Math.ceil(((player.videoWait?.deadlineAt || 0) - now.value) / 1000)));
const timer = setInterval(() => { now.value = Date.now(); }, 500);
const retryRemaining = computed(() => Math.max(0, Math.ceil(((player.sourceRetryAt || 0) - now.value) / 1000)));
const refresh = () => player.refreshVideoSource('USER_REQUEST');
const toggleExpanded = async () => {
  if (expanded.value) { expanded.value = false; if (document.fullscreenElement === container.value) await document.exitFullscreen().catch(() => {}); return; }
  expanded.value = true;
  if (!window.AndroidBridge && !/iPhone|iPad|iPod/i.test(navigator.userAgent) && container.value?.requestFullscreen) {
    await container.value.requestFullscreen().catch(() => {});
  }
};
onBeforeUnmount(() => { clearInterval(timer); if (document.fullscreenElement === container.value) document.exitFullscreen().catch(() => {}); });
</script>
