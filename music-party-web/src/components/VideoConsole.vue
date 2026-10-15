<template>
  <section class="video-console"><VideoEngine :pages="pages" :retry-remaining="retryRemaining" :wait-remaining="waitRemaining" :room-status="roomStatus" /></section>
</template>
<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import VideoEngine from './VideoEngine.vue';
import { usePlayerStore } from '../stores/player';
const player=usePlayerStore(), now=ref(Date.now());
const pages=computed(()=>player.nowPlaying?.videoDescriptor?.pages||[]);
const waitRemaining=computed(()=>Math.max(0,Math.ceil(((player.videoWait?.deadlineAt||0)-now.value)/1000)));
const retryRemaining=computed(()=>Math.max(0,Math.ceil(((player.sourceRetryAt||0)-now.value)/1000)));
const roomStatus=computed(()=>{
  if(!player.synchronized) return '正在同步房间状态…';
  if(!player.config.bilibiliCookieConfigured && !player.videoFeatures.pgcInput) return '房间尚未配置 B 站 Cookie，请管理者在管理台更新。';
  if(player.videoWait?.initial) return `全员起播准备 · ${player.videoWait.readyCount} / ${player.videoWait.participantCount} 已就绪；全部缓冲完成后统一播放。`;
  if(player.videoWait) return `全员等待缓冲 · ${player.videoWait.readyCount} / ${player.videoWait.participantCount} 已就绪 · 最多再等 ${waitRemaining.value} 秒。房间播放按钮可暂停本轮等待。`;
  if(player.sourceStatus==='RESOLVING') return '房间正在获取视频源，当前进度会保留。';
  if(player.sourceStatus==='ERROR') return '视频源获取失败，当前项已保留。请查看通知、更新 Cookie 或刷新源。';
  if(player.intentPaused) return '房间已暂停；房间底栏控制全房间，本端恢复不改变房间暂停。';
  if(player.isBuffering) return '本端缓冲中；持续 2 秒后会请求全员等待。';
  return `各端直接连接 B 站视频源 · ${player.clockReady?'时间探测已就绪':'时间探测尚未就绪'}`;
});
const timer=setInterval(()=>{now.value=Date.now();},500);
onBeforeUnmount(()=>clearInterval(timer));
</script>
<style scoped>.video-console{display:flex;flex:1;min-width:0;min-height:0;overflow:hidden}</style>
