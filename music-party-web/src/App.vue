<!-- src/App.vue -->
<template>
  <!-- 全局 Toast 挂载点 -->
  <ToastNotification ref="toastInstance" />

  <div class="h-screen w-screen overflow-hidden font-sans">
    <AudioEngine />
    <!-- 1. 认证遮罩 -->
    <AuthOverlay @unlocked="userStore.isAuthPassed = true" v-if="!userStore.isAuthPassed" />

    <!-- 2. 启动页 (Start Screen) -->
    <!-- 点击 CONNECT 后保留启动页，供转场采样并在字符化前保持原画面。 -->
    <div v-if="userStore.isAuthPassed && (!hasStarted || transitioning)" data-connect-screen class="absolute inset-0 z-[100] bg-medical-50 flex flex-col items-center justify-center space-y-8" :style="{ visibility: dissolving ? 'hidden' : 'visible' }">
      <div class="text-4xl font-black tracking-tighter text-medical-900">MUSIC PARTY</div>
      <div class="text-lg font-bold text-accent -mt-6">{{ userStore.roomName }}</div>
      <div class="font-mono text-xs text-medical-400 tracking-widest">SYSTEM READY</div>
      <button
          @click="startGame"
          class="px-12 py-4 bg-strong text-white font-bold text-xl hover:bg-accent transition-colors chamfer-br"
      >
        CONNECT
      </button>
    </div>

    <!-- 3. 主界面 (当 hasStarted 为 true 时显示) -->
    <MainLayout v-if="hasStarted" :inert="transitioning"
                :style="transitioning ? { opacity: dissolving ? 1 : 0, transform: dissolving ? 'scale(1)' : 'scale(0.965)', transformOrigin: 'center', transition: dissolving ? 'opacity 870ms ease-out, transform 870ms ease-out' : 'none' } : undefined"
                @search="handleSearchClick" @settings="showSettings = true">
      <!-- 中间插槽: 视觉控制台 -->
      <CenterConsole />

      <!-- 底部插槽: 播放器 -->
      <!-- 注意：这里不使用 v-if，而是 v-show，或者因为在 MainLayout 里是 slot，
           只有 MainLayout 渲染了，它才会渲染。
           关键是 useAudio 里的逻辑已经修好了，会自动处理播放。
      -->
      <template #player>
        <PlayerControl />
      </template>
    </MainLayout>
    <ConnectDissolve v-if="transitioning" :x="clickPoint.x" :y="clickPoint.y" @dissolve="dissolving = true" @finished="finishTransition" />

    <!-- 4. 全局弹窗 -->
    <SearchModal :isOpen="showSearch" @close="showSearch = false" />
    <SettingsModal :isOpen="showSettings" @close="showSettings = false" />
    <NamePromptModal />
    <ChatOverlay v-if="hasStarted && !transitioning && !uiStore.isLiteMode" />
    <TutorialOverlay v-if="hasStarted && !transitioning && !uiStore.isLiteMode" />
    <AdminAuthModal />
    <AdminDashboard />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useEventListener } from '@vueuse/core';
import { usePlayerStore } from './stores/player';
import { useUserStore } from './stores/user';
import { useUiStore } from './stores/ui';
import { useAdminStore } from './stores/admin';
import { useToast } from './composables/useToast';

// Components
import MainLayout from './components/layout/MainLayout.vue';
import CenterConsole from './components/CenterConsole.vue';
import PlayerControl from './components/PlayerControl.vue';
import AudioEngine from './components/AudioEngine.vue';
import AuthOverlay from './components/AuthOverlay.vue';
import SearchModal from './components/SearchModal.vue';
import SettingsModal from './components/SettingsModal.vue';
import NamePromptModal from './components/NamePromptModal.vue';
import ChatOverlay from './components/ChatOverlay.vue';
import ToastNotification from './components/ToastNotification.vue';
import TutorialOverlay from './components/TutorialOverlay.vue';
import ConnectDissolve from './components/ConnectDissolve.vue';
import AdminAuthModal from './components/AdminAuthModal.vue';
import AdminDashboard from './components/AdminDashboard.vue';

const player = usePlayerStore();
const userStore = useUserStore();
const uiStore = useUiStore();
const adminStore = useAdminStore();
const hasStarted = ref(false);
const transitioning = ref(false);
const dissolving = ref(false);
const clickPoint = ref({ x: 0, y: 0 });
const showSearch = ref(false);
const showSettings = ref(false);
const toastInstance = ref(null);
const { register, info } = useToast();

const startGame = (event) => {
  if (hasStarted.value) return;
  const button = event.currentTarget.getBoundingClientRect();
  clickPoint.value = event.detail ? { x: event.clientX, y: event.clientY } : { x: button.left + button.width / 2, y: button.top + button.height / 2 };
  transitioning.value = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  hasStarted.value = true;
  player.connect();
  if (!transitioning.value) maybeShowPwaHint();
};

const finishTransition = () => {
  transitioning.value = false;
  dissolving.value = false;
  maybeShowPwaHint();
};

const maybeShowPwaHint = () => {
  try {
    if (localStorage.getItem('mp_pwa_hint_shown')) return;
    if (window.matchMedia('(display-mode: standalone)').matches) return; // 已安装 PWA
    if (!/Android|iPhone|iPad|iPod/i.test(navigator.userAgent)) return;  // 仅移动端
    localStorage.setItem('mp_pwa_hint_shown', '1');
    info('想稳定后台播放？点浏览器菜单 → 添加到主屏幕');
  } catch (e) { /* localStorage 不可用时忽略 */ }
};

// 自动性能优化：切后台自动进入精简模式
useEventListener(document, 'visibilitychange', () => {
  if (document.visibilityState === 'hidden' && hasStarted.value && !player.isPaused && uiStore.autoLiteMode) {
    uiStore.isLiteMode = true;
  }
});

const handleSearchClick = () => {
  // 简单的搜索逻辑代理
  if (userStore.isGuest) {
    userStore.setPostNameAction(() => { showSearch.value = true; });
    userStore.showNameModal = true;
  } else {
    showSearch.value = true;
  }
};

onMounted(() => {
  if (toastInstance.value) register(toastInstance.value);
});
</script>
