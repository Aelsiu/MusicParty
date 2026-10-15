<!-- src/components/layout/MainLayout.vue -->
<script setup>
import HelpIconButton from '../HelpIconButton.vue';
import OverflowText from '../OverflowText.vue';
import { ref, computed, provide, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import {sidebarLayout} from '../../utils/videoSidebarLayout';
import { CircleHelp, Settings, Users, ListMusic, Minimize2, Maximize2, Volume2, Activity } from 'lucide-vue-next';
import RoomSidebar from '../RoomSidebar.vue';
import { useChatStore } from '../../stores/chat';
import { registerBackHandler } from '../../services/backNavigation';
import QueueList from '../QueueList.vue';
import CoverImage from '../CoverImage.vue';
import RoundSwitch from '../RoundSwitch.vue';
import VolumeControl from '../VolumeControl.vue';
import RoomNavigationActions from '../RoomNavigationActions.vue';
import { useUserStore } from '../../stores/user';
import { useUiStore } from '../../stores/ui';
import { usePlayerStore } from '../../stores/player';
import { createVideoPresentationContext, videoPresentationKey } from '../../composables/videoPresentationContext';

const emit = defineEmits(['search', 'help', 'settings', 'return', 'presentation-change']);
const userStore = useUserStore();
const uiStore = useUiStore();
const playerStore = usePlayerStore();
const chatStore = useChatStore();
const layoutRoot=ref(null),layoutWidth=ref(globalThis.window?.innerWidth||1024),layoutHeight=ref(globalThis.window?.innerHeight||768);
const desktopLayout=computed(()=>sidebarLayout(playerStore.mediaMode,layoutWidth.value,layoutHeight.value));
const sidebarWide=computed(()=>desktopLayout.value.members),queueWide=computed(()=>desktopLayout.value.queue);
const measureSidebar=()=>{layoutWidth.value=layoutRoot.value?.clientWidth||globalThis.window?.innerWidth||layoutWidth.value;layoutHeight.value=layoutRoot.value?.clientHeight||globalThis.window?.innerHeight||layoutHeight.value;};
let layoutObserver;
onMounted(()=>{measureSidebar();if(globalThis.ResizeObserver){layoutObserver=new ResizeObserver(measureSidebar);layoutObserver.observe(layoutRoot.value);}});
onBeforeUnmount(()=>layoutObserver?.disconnect());
onMounted(()=>globalThis.window?.addEventListener?.('resize',measureSidebar));
onBeforeUnmount(()=>globalThis.window?.removeEventListener?.('resize',measureSidebar));
const inlineDock = ref(null), footerFrame = ref(null), presentation = createVideoPresentationContext(inlineDock);
provide(videoPresentationKey, presentation);
watch(presentation.expanded, expanded => emit('presentation-change', expanded), { immediate: true, flush: 'sync' });
let releaseFooterFocus, footerPointerFocus = false;
let dockMoveGeneration = 0;
watch(presentation.roomDock, async () => {
    const active = globalThis.document?.activeElement, restore = !!active && footerFrame.value?.contains(active), move = ++dockMoveGeneration;
    presentation.dockMoving.value = true;
    await nextTick();
    if (move !== dockMoveGeneration) return;
    if (restore && active.isConnected !== false && footerFrame.value?.contains(active)
        && (!presentation.expanded.value || presentation.controlsVisible.value)) active.focus({ preventScroll: true });
    presentation.dockMoving.value = false;
});
watch(presentation.registration, () => { releaseFooterFocus?.(); releaseFooterFocus = null; });
const footerEditingFocus = target => target?.isContentEditable || target?.tagName === 'TEXTAREA'
    || target?.tagName === 'INPUT' && !['range','checkbox','radio','button','submit','reset'].includes(target.type);
const footerPointerDown = event => {
    footerPointerFocus = true;
    if (!footerEditingFocus(event.target)) { releaseFooterFocus?.(); releaseFooterFocus = null; }
};
const footerFocusIn = event => {
    if (!footerPointerFocus || footerEditingFocus(event.target)) releaseFooterFocus ||= presentation.acquireInteractionLock('focus');
};
const footerKeyboardFocus = event => {
    footerPointerFocus = false;
    if (footerFrame.value?.contains(event.target)) footerFocusIn(event);
};
const footerFocusOut = event => { if (!event.currentTarget.contains(event.relatedTarget)) { releaseFooterFocus?.(); releaseFooterFocus = null; } };
onBeforeUnmount(() => { dockMoveGeneration++; releaseFooterFocus?.(); globalThis.document?.removeEventListener?.('keydown', footerKeyboardFocus, true); presentation.dispose(); emit('presentation-change', false); });

onMounted(() => {
  uiStore.fetchConfig();
  globalThis.document?.addEventListener?.('keydown', footerKeyboardFocus, true);
});

const mobileQueueOpen = ref(false);
const mobileUserOpen = ref(false);
watch(sidebarWide,value=>{if(value)mobileUserOpen.value=false;});
watch(queueWide,value=>{if(value)mobileQueueOpen.value=false;});
const keyboardViewportHeight=ref(null);
const measureKeyboardViewport=()=>{
  const viewport=globalThis.window?.visualViewport,focused=globalThis.document?.activeElement;
  keyboardViewportHeight.value=viewport&&!sidebarWide.value&&mobileUserOpen.value&&focused?.tagName==='INPUT'&&focused.type!=='range'
    ? `${Math.min(window.innerHeight,viewport.height+viewport.offsetTop)}px` : null;
};
watch([mobileUserOpen,sidebarWide],measureKeyboardViewport);
onMounted(()=>{globalThis.window?.visualViewport?.addEventListener?.('resize',measureKeyboardViewport);globalThis.window?.visualViewport?.addEventListener?.('scroll',measureKeyboardViewport);globalThis.document?.addEventListener?.('focusin',measureKeyboardViewport);globalThis.document?.addEventListener?.('focusout',measureKeyboardViewport);});
onBeforeUnmount(()=>{globalThis.window?.visualViewport?.removeEventListener?.('resize',measureKeyboardViewport);globalThis.window?.visualViewport?.removeEventListener?.('scroll',measureKeyboardViewport);globalThis.document?.removeEventListener?.('focusin',measureKeyboardViewport);globalThis.document?.removeEventListener?.('focusout',measureKeyboardViewport);});
const unregisterDrawerBack=registerBackHandler(80,()=>{if(presentation.expanded.value||uiStore.effectiveLiteMode)return false;if(mobileUserOpen.value){mobileUserOpen.value=false;return true;}if(mobileQueueOpen.value){mobileQueueOpen.value=false;return true;}return false;});
onBeforeUnmount(unregisterDrawerBack);

const toggleMobileQueue = () => {
  mobileQueueOpen.value = !mobileQueueOpen.value;
  if(mobileQueueOpen.value) mobileUserOpen.value = false;
};

const toggleMobileUser = () => {
  mobileUserOpen.value = !mobileUserOpen.value;
  if(mobileUserOpen.value) mobileQueueOpen.value = false;
};

const handleSearchClick = () => {
  emit('search');
}
</script>

<template>
  <div ref="layoutRoot" class="h-[100dvh] w-screen flex flex-col relative overflow-hidden bg-medical-50" :style="keyboardViewportHeight ? {height:keyboardViewportHeight} : null">
    <!-- 1. 顶部栏 Header -->
    <header v-if="!uiStore.effectiveLiteMode" v-show="!presentation.expanded.value" :inert.prop="presentation.expanded.value" class="room-header min-h-14 bg-surface border-b border-medical-200 flex justify-between items-center px-4 md:px-6 flex-shrink-0 relative z-50">
      <div class="room-brand flex items-center gap-2 min-w-0">
        <img src="/icons/icon-192.png" alt="" class="w-6 h-6 md:w-7 md:h-7 object-contain flex-shrink-0" />
        <div class="room-brand-text flex items-baseline gap-1">
          <a href="https://github.com/Aelsiu/MusicParty" target="_blank" rel="noopener noreferrer" class="font-black text-base md:text-xl tracking-tighter text-medical-900 whitespace-nowrap hover:text-accent transition-colors">Mirth Party</a>
          <span class="room-author text-medical-300 font-mono font-normal text-[10px] md:text-xs">by {{ uiStore.authorName }}</span>
        </div>
      </div>

      <div class="room-tools flex items-center gap-2 md:gap-4" :class="{ 'music-toolbar': playerStore.mediaMode === 'MUSIC' }">
        <!-- 移动端：显示人数按钮 -->
        <button
            id="tutorial-rename-mobile"
            data-guide="members"
            v-show="!sidebarWide"
            @click="toggleMobileUser"
            aria-label="在线成员" :aria-expanded="mobileUserOpen" aria-controls="room-members-mobile"
            class="relative flex items-center justify-center w-9 h-9 bg-medical-50 border border-medical-200 text-medical-500 hover:text-medical-900 transition-colors overflow-hidden group rounded-sm transform-gpu"
            :class="{ 'bg-medical-200 text-medical-900 border-medical-300': mobileUserOpen }"
        >
          <span
              class="absolute inset-0 flex items-center justify-center font-black text-4xl leading-none text-accent/15 pointer-events-none z-0 select-none scale-110 font-mono"
          >
            {{ userStore.onlineUsers.length > 9 ? 'N' : userStore.onlineUsers.length }}
          </span>
          <Users class="w-5 h-5 relative z-10" />
          <span v-if="chatStore.hasUnread" class="absolute top-1 right-1 w-1.5 h-1.5 rounded-full bg-accent" aria-label="有未读聊天消息" />
        </button>

        <button v-show="!queueWide" id="tutorial-queue-mobile" data-guide="queue" @click="toggleMobileQueue" aria-label="播放队列" :aria-expanded="mobileQueueOpen" aria-controls="room-queue-mobile"
                class="flex items-center justify-center w-9 h-9 border border-medical-200 bg-medical-50 text-medical-600 rounded-sm"
                :class="{ 'bg-medical-200 text-medical-900': mobileQueueOpen }"><ListMusic class="w-5 h-5" /></button>

        <!-- 精简模式按钮 -->
        <button
            v-if="playerStore.mediaMode === 'MUSIC'"
            data-guide="lite"
            @click="uiStore.toggleLiteMode"
            class="flex items-center justify-center w-9 h-9 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-600 transition-all rounded-sm"
            title="精简模式" aria-label="精简模式"
        >
          <Minimize2 class="w-4 h-4" />
        </button>

        <!-- 搜索按钮 -->
        <HelpIconButton data-guide="help" @click="emit('help')" />
        <button data-guide="settings" @click="emit('settings')" aria-label="设置" title="设置" class="flex items-center justify-center w-9 h-9 border border-medical-200 bg-medical-50 hover:bg-medical-100 text-medical-700 transition-colors rounded-sm">
          <Settings class="w-4 h-4" />
        </button>
      </div>
    </header>
    <nav v-show="!presentation.expanded.value" :inert.prop="presentation.expanded.value" class="flex items-center justify-between gap-3 px-4 py-1.5 border-b border-medical-200 bg-surface text-[11px] text-medical-400 shrink-0" aria-label="房间导航">
      <div class="room-nav-details flex items-center gap-2 sm:gap-3 min-w-0">
        <OverflowText class="room-nav-name min-w-0 text-accent font-bold" :text="userStore.roomName" />
        <span class="font-mono whitespace-nowrap shrink-0">ID {{ userStore.roomId }}</span>
      </div>
      <RoomNavigationActions @return="emit('return')" />
    </nav>

    <!-- 2. 主体内容区 -->
    <div v-if="!uiStore.effectiveLiteMode" class="flex-1 flex min-w-0 min-h-0 overflow-hidden relative">
      <aside id="room-members-mobile" v-show="!presentation.expanded.value&&(sidebarWide||mobileUserOpen)" :inert.prop="presentation.expanded.value || (!sidebarWide && !mobileUserOpen)" :data-guide-obscured="mobileQueueOpen ? 'true' : null" class="members-sidebar desktop-sidebar bg-medical-50 border-r border-medical-200" :class="{'drawer-open':mobileUserOpen,'drawer-layout':!sidebarWide}">
        <div class="flex-1 min-h-0"><RoomSidebar :visible="!uiStore.effectiveLiteMode && !presentation.expanded.value && (sidebarWide || mobileUserOpen)" /></div>
      </aside>

      <main :data-guide-obscured="mobileQueueOpen || mobileUserOpen ? 'true' : null" class="flex-1 min-w-0 min-h-0 bg-medical-100/30 relative flex flex-col overflow-hidden z-10">
        <slot></slot>
      </main>

      <aside v-show="!presentation.expanded.value&&queueWide" :inert.prop="presentation.expanded.value||!queueWide" :data-guide-obscured="mobileUserOpen ? 'true' : null" class="desktop-sidebar queue-sidebar bg-surface border-l border-medical-200 overflow-hidden">
        <QueueList @search="handleSearchClick" />
      </aside>

      <Transition name="slide-fade">
        <div id="room-queue-mobile" v-if="mobileQueueOpen&&!queueWide" v-show="!presentation.expanded.value" :inert.prop="presentation.expanded.value" class="absolute inset-0 bg-surface z-30 overflow-y-auto overscroll-contain">
          <QueueList @search="handleSearchClick" />
        </div>
      </Transition>

    </div>

    <!-- 3. 精简模式视图 -->
    <div v-else class="flex-1 flex flex-col items-center justify-center bg-medical-50 relative overflow-hidden p-4 md:p-6">
      <!-- 背景装饰 -->
      <div class="absolute inset-0 z-0 pointer-events-none opacity-40">
        <div class="absolute inset-0 bg-[linear-gradient(rgba(17,24,39,0.03)_1px,transparent_1px),linear-gradient(90deg,rgba(17,24,39,0.03)_1px,transparent_1px)] bg-[size:32px_32px]"></div>
        <div class="absolute top-0 left-0 w-full h-full bg-[repeating-linear-gradient(45deg,transparent,transparent_20px,rgba(0,0,0,0.02)_20px,rgba(0,0,0,0.02)_21px)]"></div>
      </div>

      <div class="relative z-10 w-full max-w-lg flex flex-col items-center gap-10">
        <!-- 头部状态 (文案简化) -->
        <div class="flex items-center gap-3 text-[10px] text-medical-400 tracking-[0.2em] uppercase font-sans">
          <Activity class="w-3 h-3 text-accent animate-pulse" />
          <span>精简模式</span>
        </div>

        <!-- 核心显示卡片 -->
        <div class="w-full bg-surface border border-medical-200 shadow-2xl relative p-5 md:p-8 chamfer-br">
           <!-- 四角修饰 -->
          <div class="absolute top-2 left-2 w-2 h-2 border-t border-l border-medical-300"></div>
          <div class="absolute top-2 right-2 w-2 h-2 border-t border-r border-medical-300"></div>
          <div class="absolute bottom-2 left-2 w-2 h-2 border-b border-l border-medical-300"></div>
          <div class="absolute bottom-2 right-2 w-2 h-2 border-b border-r border-medical-300"></div>

          <div class="flex flex-col md:flex-row items-center gap-8">
            <!-- 封面区 -->
            <div class="relative flex-shrink-0">
               <div class="w-24 h-24 border-2 border-medical-100 flex items-center justify-center bg-medical-50 shadow-inner overflow-hidden">
                  <CoverImage :src="playerStore.nowPlaying?.music.coverUrl" class="w-full h-full object-cover" />
               </div>
               <!-- 状态环 -->
               <div v-if="!playerStore.isPaused" class="absolute -inset-2 border border-accent/20 animate-[spin_8s_linear_infinite] rounded-full border-dashed"></div>
            </div>

            <!-- 歌曲信息区 -->
            <div class="flex-1 min-w-0 flex flex-col items-center md:items-start text-center md:text-left font-sans">
               <span class="text-[10px] font-mono text-accent mb-1 tracking-widest uppercase">正在播放</span>
               <OverflowText as="h2" class="w-full text-2xl md:text-3xl font-black text-medical-900 tracking-tighter leading-tight mb-2" :text="playerStore.nowPlaying?.music.name || '系统待机'" />
               <div class="flex items-center gap-2 text-sm font-bold text-medical-500">
                 <span class="w-2 h-2 bg-medical-200"></span>
                 {{ playerStore.nowPlaying?.music.artists.join(' / ') || '无内容' }}
               </div>
            </div>
          </div>
        </div>

        <!-- 音量控制 -->
        <div class="w-full max-w-[320px] bg-surface/80 backdrop-blur-sm border border-medical-200 p-4 flex flex-col gap-3 shadow-sm">
           <span class="text-[10px] font-mono text-medical-400">音量</span>
           <VolumeControl />
        </div>

        <!-- 后台自动精简开关 (变色优化) -->
        <label class="flex items-center gap-2 cursor-pointer group select-none">
           <RoundSwitch v-model="uiStore.autoLiteMode" label="后台播放时自动进入精简模式" />
           <span class="text-[10px] font-mono text-medical-400 group-hover:text-medical-600 transition-colors">
              后台播放时自动进入精简模式
           </span>
        </label>

        <!-- 退出动作 -->
        <button
            @click="uiStore.toggleLiteMode"
            class="w-full bg-strong text-white font-black py-4 flex items-center justify-center gap-4 transition-all hover:bg-accent hover:tracking-[0.2em] active:scale-[0.98] group shadow-xl"
        >
          <Maximize2 class="w-5 h-5 transition-transform group-hover:scale-110" />
          <span class="text-sm tracking-widest uppercase">退出精简模式</span>
        </button>
      </div>
    </div>

    <div id="musicparty-room-inline-dock" ref="inlineDock" class="shrink-0"></div>
    <Teleport defer :to="presentation.roomDock.value || '#musicparty-room-inline-dock'">
      <div ref="footerFrame" v-show="!uiStore.effectiveLiteMode && (!presentation.expanded.value || presentation.controlsVisible.value)"
           :inert.prop="presentation.expanded.value && !presentation.controlsVisible.value" class="w-full shrink-0"
           @pointerdown.capture="footerPointerDown" @focusin="footerFocusIn" @focusout="footerFocusOut">
        <slot name="player"></slot>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.members-sidebar{display:flex;flex-direction:column;flex-shrink:0;min-height:0;overflow:hidden}
.members-sidebar.drawer-layout{display:none;position:absolute;inset:0;width:100%;flex-basis:auto;z-index:30}.members-sidebar.drawer-layout.drawer-open{display:flex}
.desktop-sidebar{flex-shrink:0;box-sizing:border-box}
@media(min-width:768px){.desktop-sidebar{width:20rem;flex-basis:20rem}}
.room-brand{max-width:100%}
.room-brand-text{min-width:0}
.room-header{flex-wrap:wrap;gap:4px 12px;padding-top:8px;padding-bottom:8px}
.room-tools{margin-left:auto;justify-content:flex-end;flex-shrink:0;flex-wrap:nowrap}
.room-tools > :deep(button){flex-shrink:0}
@media(max-width:767px){.room-brand-text{flex-direction:column;align-items:flex-start;gap:0}.room-author{overflow-wrap:anywhere}.members-sidebar{flex-basis:auto}}
@media(pointer:coarse){.room-tools > :deep(button){width:44px;height:44px;min-width:44px}}
.music-toolbar > :deep(button){width:36px;height:36px;min-width:36px;flex-shrink:0}
@media(pointer:coarse){.music-toolbar > :deep(button){width:44px;height:44px;min-width:44px}}
@media(pointer:coarse) and (max-width:767px){.music-toolbar{gap:4px}}
/* Compensate for the room name font's lower visual center beside the monospace ID. */
.room-nav-name{transform:translateY(-1px)}
@media(max-width:420px){.room-nav-details{flex-wrap:wrap;gap:0 8px}.room-nav-name{flex-basis:100%}}
.slide-fade-enter-active, .slide-fade-leave-active { transition: all 0.3s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { transform: translateY(10px); opacity: 0; }

/* 自定义 Range 样式以匹配工业风 */
input[type=range]::-webkit-slider-thumb {
  -webkit-appearance: none;
  height: 14px;
  width: 6px;
  border-radius: 0;
  background: rgb(var(--strong));
  cursor: pointer;
  border: 1px solid white;
  margin-top: -5px; /* 确保滑块在轨道中央 */
  box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
input[type=range]:hover::-webkit-slider-thumb {
  background: rgb(var(--accent));
}
input[type=range]::-webkit-slider-runnable-track {
  width: 100%;
  height: 4px;
  background: rgb(var(--medical-200));
}
</style>
