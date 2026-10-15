<template>
  <button type="button" @click="show" :disabled="flow.busy.value" class="w-full bg-accent text-white py-3 font-bold text-sm flex items-center justify-center gap-2">
    <QrCode class="w-4 h-4" /> 获取 Cookie
  </button>
  <Teleport to="body">
    <div v-if="flow.open.value" role="dialog" aria-modal="true" aria-label="B站扫码登录"
         class="fixed inset-0 z-[140] bg-overlay/60 backdrop-blur-sm flex items-center justify-center p-4 text-medical-900 font-sans"
         @click.self="close" @keydown.esc.stop.prevent="close" @keydown.tab="trapFocus">
      <section ref="dialog" class="bg-surface border border-medical-200 chamfer-br w-full max-w-sm shadow-2xl">
        <header class="p-4 border-b border-medical-200 bg-medical-50 flex items-center justify-between">
          <h3 class="font-mono text-sm font-bold">BILIBILI LOGIN</h3>
          <button ref="closeButton" type="button" @click="close" aria-label="关闭B站扫码登录" class="p-2"><X class="w-4 h-4" /></button>
        </header>
        <div class="p-6 text-center">
          <img v-if="flow.image.value" :src="flow.image.value" alt="B站登录二维码" class="w-56 h-56 mx-auto bg-white" />
          <div v-else class="h-40 flex items-center justify-center">
            <Loader2 v-if="flow.busy.value" class="w-8 h-8 animate-spin" />
            <CheckCircle2 v-else-if="flow.state.value === 'SUCCESS'" class="w-12 h-12 text-accent" />
            <QrCode v-else class="w-12 h-12 text-medical-300" />
          </div>
          <p class="font-bold text-sm mt-4" aria-live="polite">{{ labels[flow.state.value] || '正在创建二维码' }}</p>
          <p v-if="flow.error.value" role="alert" class="text-xs text-red-500 mt-3">{{ flow.error.value }}</p>
          <p class="text-xs text-medical-500 leading-relaxed mt-3">使用哔哩哔哩 App 扫码并确认。登录凭据仅保存在当前房间服务端，用于 B站音乐和视频播放。</p>
          <button v-if="['EXPIRED', 'FAILED'].includes(flow.state.value)" type="button" @click="flow.start" :disabled="flow.busy.value" class="w-full bg-accent text-white py-3 mt-5 font-bold text-sm">重新扫码</button>
          <button type="button" @click="close" class="w-full border border-medical-200 py-3 mt-3 text-sm font-bold">{{ flow.state.value === 'SUCCESS' ? '完成' : '取消' }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
<script setup>
import { ref, watch, nextTick, onBeforeUnmount } from 'vue';
import { QrCode, X, Loader2, CheckCircle2 } from 'lucide-vue-next';
import { useBilibiliQrLogin } from '../composables/useBilibiliQrLogin';
import { roomSession } from '../services/roomSession';
import { registerBackHandler } from '../services/backNavigation';
const flow = useBilibiliQrLogin(), dialog = ref(null), closeButton = ref(null);
const emit = defineEmits(['saved']);
const labels = { WAITING: '等待扫码', CONFIRMING: '已扫码，请在手机上确认', VALIDATING: '正在验证并保存凭据', SUCCESS: 'Cookie 已保存', EXPIRED: '二维码已过期', FAILED: '登录失败，请重试' };
let returnFocus;
function show(event) { returnFocus = event.currentTarget; flow.start(); }
function close() { flow.close(); if (returnFocus?.isConnected) nextTick(() => returnFocus.focus()); }
function trapFocus(event) {
  const elements = [...(dialog.value?.querySelectorAll('button:not(:disabled)') || [])];
  if (!elements.length) return;
  const target = event.shiftKey ? elements.at(-1) : elements[0];
  const edge = event.shiftKey ? elements[0] : elements.at(-1);
  if (event.target === edge) { event.preventDefault(); target.focus(); }
}
watch(flow.open, async value => { if (value) { await nextTick(); closeButton.value?.focus(); } });
watch(flow.state, value => { if (value === 'SUCCESS') emit('saved'); });
watch(() => [roomSession.roomId, roomSession.managerToken, roomSession.generation], close);
const unregister = registerBackHandler(140, () => { if (!flow.open.value) return false; close(); return true; });
onBeforeUnmount(() => { flow.close(); unregister(); });
</script>
