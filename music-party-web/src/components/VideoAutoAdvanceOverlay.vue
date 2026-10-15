<script setup>
import { useVideoCountdown } from '../composables/useVideoCountdown.js';
const props = defineProps({ transition: { type: Object, required: true } });
const { fraction } = useVideoCountdown(() => props.transition);
</script>

<template>
  <div class="video-auto-advance" role="status" aria-live="polite">
    <svg class="countdown-ring" viewBox="0 0 56 56" aria-hidden="true">
      <circle class="ring-track" cx="28" cy="28" r="23" />
      <circle class="ring-progress" cx="28" cy="28" r="23" pathLength="1" stroke-dasharray="1" :stroke-dashoffset="1-fraction" />
    </svg>
    <span>正在准备视频</span>
  </div>
</template>

<style scoped>
.video-auto-advance{position:absolute;inset:0;z-index:5;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:#000;color:#fff;font-size:14px;pointer-events:none}
.countdown-ring{width:56px;height:56px;transform:rotate(-90deg);fill:none;stroke-width:3}
.ring-track{stroke:rgba(255,255,255,.2)}.ring-progress{stroke:currentColor;stroke-linecap:butt}
</style>
