<template>
  <!--
    外层容器
    pointer-events-none: 确保透明区域不挡住下面内容的点击
    z-[100]: 确保在大多数内容之上
  -->
  <div
      :style="{ left: x + 'px', top: y + 'px' }"
      class="fixed z-[100] flex flex-col items-center touch-none pointer-events-none"
  >

    <!--
      聊天窗口
      pointer-events-auto: HB恢复内部点击
    -->
    <Transition
        enter-active-class="transition-all duration-300 ease-out"
        enter-from-class="opacity-0 scale-95"
        enter-to-class="opacity-100 scale-100"
        leave-active-class="transition-all duration-200 ease-in"
        leave-from-class="opacity-100 scale-100"
        leave-to-class="opacity-0 scale-95"
        @after-enter="() => scrollToBottom(true)"
    >
      <div
          v-if="chatStore.isOpen"
          class="pointer-events-auto bg-surface border border-medical-200 shadow-2xl flex flex-col chamfer-br overflow-hidden"
          :class="dynamicWindowClasses"
          @mousedown.stop
          @touchstart.stop
      >
        <!--
           1. Header (支持拖拽)
           cursor-move: 提示可拖拽
        -->
        <div
            ref="windowHeaderRef"
            @pointerdown="startHeaderDrag"
            class="h-10 bg-medical-50 border-b border-medical-200 flex items-center justify-between px-3 flex-shrink-0 select-none"
            :class="{ 'cursor-move': !isMobile }"
        >
          <div class="font-mono text-xs font-bold text-medical-500 flex items-center gap-2">
            <MessageSquare class="w-3 h-3"/> CHAT
          </div>
          <button @click="chatStore.toggleChat" class="text-medical-400 hover:text-medical-900 p-1 cursor-pointer">
            <X class="w-4 h-4"/>
          </button>
        </div>

        <!-- 2. Tabs 切换栏 -->
        <div class="flex border-b border-medical-200 bg-medical-50/50">
          <button
              v-for="tab in ['CHAT', 'SYSTEM']"
              :key="tab"
              @click="activeTab = tab"
              class="flex-1 py-2 text-[10px] font-bold font-mono transition-colors relative"
              :class="activeTab === tab ? 'text-medical-900 bg-surface' : 'text-medical-400 hover:text-medical-600 hover:bg-medical-100'"
          >
            {{ tab }}
            <!-- 激活指示条 -->
            <div v-if="activeTab === tab" class="absolute top-0 left-0 w-full h-0.5 bg-accent"></div>
          </button>
        </div>

        <!-- 3. Messages List -->
        <div
            ref="msgListRef"
            @scroll="handleScroll"
            class="flex-1 overflow-y-auto p-3 space-y-4 bg-medical-50/30 chat-scroll"
        >
          <!-- Loading More Indicator -->
          <div v-if="chatStore.isLoadingMore" class="flex justify-center py-2">
            <Loader2 class="w-4 h-4 animate-spin text-accent/50" />
          </div>

          <div v-if="processedMessages.length === 0" class="text-center py-8 text-[10px] text-medical-300 font-mono">
            > NO RECORDS IN {{ activeTab }}
          </div>

          <div
              v-for="(item, index) in processedMessages"
              :key="item.msg.id"
          >
            <!-- 时间戳 (如果与上一条间隔超过3分钟则显示) -->
            <div v-if="item.showTime" class="flex justify-center mb-3">
              <span class="text-[9px] font-mono text-medical-300 bg-medical-100/50 px-2 py-0.5 rounded-sm">
                {{ formatTime(item.msg.timestamp) }}
              </span>
            </div>

            <!-- 消息体 -->
            <!-- 情况A: 聊天消息 (CHAT) -->
            <div
                v-if="item.msg.type === 'CHAT'"
                class="flex flex-col text-sm group"
                :class="isSelf(item.msg) ? 'items-end' : 'items-start'"
            >
              <div class="flex items-center gap-2 text-[10px] text-medical-400 mb-0.5 font-sans">
                <span v-if="!isSelf(item.msg)">{{ userStore.resolveName(item.msg.userId, item.msg.userName) }}</span>
              </div>
              <div
                  class="max-w-[90%] px-3 py-1.5 text-xs break-words relative shadow-sm leading-relaxed select-text cursor-text"
                  :class="isSelf(item.msg)
                    ? 'bg-strong text-white rounded-l-md rounded-tr-md'
                    : 'bg-surface border border-medical-200 text-medical-800 rounded-r-md rounded-tl-md'"
              >
                {{ item.msg.content }}
              </div>
            </div>

            <!-- 情况B: 系统日志 (SYSTEM) -->
            <div
                v-else-if="item.msg.type === 'SYSTEM'"
                class="flex items-start gap-2 text-xs text-medical-500/80 px-2 opacity-80"
            >
              <Terminal class="w-3 h-3 mt-0.5 flex-shrink-0 opacity-50"/>
              <span class="font-sans text-[10px] leading-relaxed break-all select-text cursor-text">
                <span v-if="item.msg.private" class="text-accent">[仅自己可见] </span>
                {{ item.msg.content }}
              </span>
            </div>

            <!-- 情况C: 点赞 (LIKE) -->
            <div
                v-else-if="item.msg.type === 'LIKE'"
                class="flex justify-center my-1"
            >
              <div class="bg-accent/5 border border-accent/20 text-accent px-3 py-1 rounded-full text-[10px] font-bold flex items-center gap-1 shadow-sm">
                <Zap class="w-3 h-3 fill-accent stroke-accent"/>
                <span>{{ item.msg.content }}</span>
              </div>
            </div>

            <!-- 情况D: 开始播放 (PLAY_START) -->
            <div
                v-else-if="item.msg.type === 'PLAY_START'"
                class="flex justify-center my-2"
            >
               <div class="bg-medical-100/80 border border-medical-300 text-medical-600 px-3 py-1 rounded-md text-[10px] font-mono flex items-center gap-2 shadow-sm">
                 <span class="w-2 h-2 rounded-full bg-accent animate-pulse"></span>
                 <span>{{ item.msg.content }}</span>
               </div>
            </div>

          </div>
        </div>

        <!-- 4. Input Area (仅在 Chat Tab 显示) -->
        <div v-if="activeTab === 'CHAT'" ref="inputAreaRef" class="relative p-2 bg-surface border-t border-medical-200 flex gap-2 flex-shrink-0">
          <div v-if="showCommands" id="chat-command-menu" role="menu" aria-label="快捷指令" class="absolute bottom-full left-2 right-2 mb-1 max-h-64 overflow-y-auto bg-surface border border-medical-200 shadow-xl z-10 p-1">
            <button v-for="command in availableCommands" :key="command.text" type="button" role="menuitem" @click="chooseCommand(command.text)" class="w-full px-2 py-2 flex items-center justify-between gap-2 text-left hover:bg-medical-50 focus-visible:bg-medical-50">
              <span class="font-mono text-xs text-accent whitespace-nowrap">{{ command.text }}</span><span class="text-[10px] text-medical-500">{{ command.label }}</span>
            </button>
          </div>
          <button type="button" @click="showCommands=!showCommands" aria-label="快捷指令" aria-haspopup="menu" aria-controls="chat-command-menu" :aria-expanded="showCommands" class="px-1 text-medical-500 hover:text-accent"><MoreVertical class="w-4 h-4" /></button>
          <input
              ref="inputRef"
              v-model="inputContent"
              @keyup.enter="send"
              @keydown.tab="completeInput"
              @keydown.esc="showCommands=false"
              @mousedown.stop
              @touchstart.stop
              placeholder="TYPE MESSAGE..."
              class="min-w-0 flex-1 bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs outline-none focus:border-accent font-sans transition-colors rounded-sm text-medical-900"
          />
          <button
              @click="send" aria-label="发送消息"
              class="bg-accent hover:bg-accent-hover text-white px-3 py-1.5 transition-colors rounded-sm flex items-center justify-center shadow-sm shadow-accent/20"
          >
            <Send class="w-4 h-4" />
          </button>
        </div>

        <!-- System Tab 底部占位 -->
        <div v-else class="h-6 bg-medical-50 border-t border-medical-200 flex items-center justify-center">
          <span class="text-[9px] font-mono text-medical-300">SYSTEM LOG READ-ONLY</span>
        </div>
      </div>
    </Transition>

    <!--
      悬浮开关按钮 (拖拽手柄)
    -->
    <div
        id="tutorial-chat"
        v-if="!isMobile || !chatStore.isOpen"
        ref="dragHandle"
        @pointerdown="handlePointerDown"
        @click="handleClick"
        class="pointer-events-auto w-10 h-10 border flex items-center justify-center transition-all cursor-move select-none rounded-sm relative overflow-hidden"
        :class="chatStore.unreadCount > 0
            ? 'bg-accent border-accent text-white shadow-[0_0_15px_rgb(var(--accent)_/_0.6)] scale-110'
            : 'bg-surface border-medical-200 text-medical-500 shadow-lg hover:text-medical-900 hover:border-medical-300'"
    >
      <div v-if="chatStore.unreadCount > 0"
           class="absolute inset-0 bg-[url('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAADCAYAAABS3WWCAAAAE0lEQVQYV2NkYGD4zwABjFAQAwBATgMJy2B8NAAAAABJRU5ErkJggg==')] opacity-30 pointer-events-none animate-scan z-0">
      </div>

      <span v-if="chatStore.unreadCount > 0" class="font-bold font-mono text-sm relative z-10 animate-pulse">
         {{ chatStore.unreadCount > 99 ? '99+' : chatStore.unreadCount }}
      </span>

      <MessageSquare v-else class="w-5 h-5 relative z-10"/>
    </div>

  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed } from 'vue';
import { useChatStore } from '../stores/chat';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useToast } from '../composables/useToast';
import { useDraggable, useWindowSize, useEventListener, onClickOutside, clamp } from '@vueuse/core';
import { MessageSquare, X, Send, Terminal, Zap, Loader2, MoreVertical } from 'lucide-vue-next';
import dayjs from 'dayjs';
import { commandChoices, completeCommand, parseChatCommand } from '../utils/chatCommands.js';
import { roomSession } from '../services/roomSession';
import { isShareCommand } from '../services/shareInvite.js';

const chatStore = useChatStore();
const playerStore = usePlayerStore();
const userStore = useUserStore();
const { success, error } = useToast();
const { width: windowWidth, height: windowHeight } = useWindowSize();

// 新增: 创建一个计算属性来判断是否为移动端
const isMobile = computed(() => windowWidth.value < 768);

const inputContent = ref('');
const inputRef = ref(null), inputAreaRef = ref(null), showCommands = ref(false);
const availableCommands = computed(() => commandChoices(roomSession.ownerAccess));
onClickOutside(inputAreaRef, () => { showCommands.value = false; });
async function chooseCommand(text) {
  inputContent.value = text; showCommands.value = false;
  await nextTick(); inputRef.value?.focus();
}
function completeInput(event) {
  if (event.shiftKey || event.isComposing || inputRef.value?.selectionStart !== inputContent.value.length
      || inputRef.value?.selectionEnd !== inputContent.value.length) return;
  const completed = completeCommand(inputContent.value, roomSession.ownerAccess);
  if (!completed || completed === inputContent.value) return;
  event.preventDefault(); inputContent.value = completed;
}
const msgListRef = ref(null);
const dragHandle = ref(null);
const windowHeaderRef = ref(null);

const activeTab = ref('CHAT'); // 'CHAT' | 'SYSTEM'

const BUTTON_SIZE = 40;
const MARGIN = 10;

// === 1. 拖拽逻辑 (主控制器) ===
const { x, y } = useDraggable(dragHandle, {
  initialValue: { x: window.innerWidth - 60, y: window.innerHeight - 150 },
  preventDefault: true,
  onMove: (position) => {
    position.x = clamp(position.x, MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN);
    position.y = clamp(position.y, MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN);
  }
});

// === 2. 标题栏拖拽同步逻辑 ===
const startHeaderDrag = (e) => {
  // 修改: 在移动端禁用此功能
  if (isMobile.value) return;

  const startMouseX = e.clientX;
  const startMouseY = e.clientY;
  const startX = x.value;
  const startY = y.value;

  const onMouseMove = (me) => {
    let newX = startX + (me.clientX - startMouseX);
    let newY = startY + (me.clientY - startMouseY);

    newX = clamp(newX, MARGIN, window.innerWidth - BUTTON_SIZE - MARGIN);
    newY = clamp(newY, MARGIN, window.innerHeight - BUTTON_SIZE - MARGIN);

    x.value = newX;
    y.value = newY;
  };

  const onMouseUp = () => {
    window.removeEventListener('pointermove', onMouseMove);
    window.removeEventListener('pointerup', onMouseUp);
  };

  window.addEventListener('pointermove', onMouseMove);
  window.addEventListener('pointerup', onMouseUp);
};

// === 3. 点击与防误触 ===
let startDragPos = { x: 0, y: 0 };
const handlePointerDown = (e) => {
  startDragPos = { x: e.clientX, y: e.clientY };
};
const handleClick = (e) => {
  const dx = Math.abs(e.clientX - startDragPos.x);
  const dy = Math.abs(e.clientY - startDragPos.y);
  if (dx > 5 || dy > 5) return; // 位移过大视为拖拽

  if (userStore.isGuest) {
    userStore.setPostNameAction(() => {
      if(!chatStore.isOpen) chatStore.toggleChat();
    });
    userStore.showNameModal = true;
    return;
  }
  chatStore.toggleChat();
};

// === 4. 窗口智能定位 ===
const isRightSide = computed(() => x.value > windowWidth.value / 2);
const isBottomSide = computed(() => y.value > windowHeight.value / 2);

const windowPositionClasses = computed(() => {
  const classes = [];
  if (isRightSide.value) classes.push('right-12'); else classes.push('left-12');
  if (isBottomSide.value) classes.push('bottom-0'); else classes.push('top-0');
  return classes.join(' ');
});

// 新增: 动态计算窗口的样式类
const dynamicWindowClasses = computed(() => {
  if (isMobile.value) {
    // 移动端: 返回固定居中的模态框样式
    return ['fixed', 'inset-0', 'm-auto', 'w-[90vw]', 'h-[75vh]', 'max-h-[600px]', 'max-w-[420px]'];
  }
  // PC端: 返回原有的绝对定位浮窗样式
  return ['absolute', 'w-[85vw]', 'max-w-[340px]', 'h-[50vh]', 'md:h-[480px]', windowPositionClasses.value];
});


const resetPosition = () => {
  x.value = clamp(x.value, MARGIN, windowWidth.value - BUTTON_SIZE - MARGIN);
  y.value = clamp(y.value, MARGIN, windowHeight.value - BUTTON_SIZE - MARGIN);
};
useEventListener(window, 'resize', resetPosition);
resetPosition(); // 初始化时执行一次

// === 5. 消息处理与展示逻辑 ===
const isSelf = (msg) => msg.userId === userStore.userToken;

const formatTime = (ts) => dayjs(ts).format('MM-DD HH:mm');

// 核心：过滤并计算时间戳显示
const processedMessages = computed(() => {
  // 1. 根据 Tab 过滤
  const filtered = chatStore.messages.filter(msg => {
    // CHAT Tab: 聊天 + 点赞 + 开始播放
    if (activeTab.value === 'CHAT') {
      return msg.type === 'CHAT' || msg.type === 'LIKE' || msg.type === 'PLAY_START' || (msg.type === 'SYSTEM' && msg.private);
    }
    // SYSTEM Tab: 系统 + 点赞 + 开始播放
    if (activeTab.value === 'SYSTEM') {
      return (msg.type === 'SYSTEM' && !msg.private) || msg.type === 'LIKE' || msg.type === 'PLAY_START';
    }
    return false;
  });

  // 2. 计算是否显示时间
  const result = [];
  let lastTime = 0;
  const TIME_THRESHOLD = 3 * 60 * 1000; // 3分钟

  for (const msg of filtered) {
    let showTime = false;
    if (msg.timestamp - lastTime > TIME_THRESHOLD) {
      showTime = true;
      lastTime = msg.timestamp;
    }
    result.push({ msg, showTime });
  }

  return result;
});

// === 6. 滚动与分页逻辑 ===
const scrollToBottom = async (force = false) => {
  await nextTick();
  if (msgListRef.value) {
    const el = msgListRef.value;
    // 只有当用户已经在底部，或者强制滚动时，才自动滚到底
    // 允许 50px 的误差
    const isAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 50;
    if (isAtBottom || force) {
      el.scrollTop = el.scrollHeight;

      // 如果是强制滚动（如打开窗口），在下一帧再次检查，防止因动画或布局导致的计算偏差
      if (force) {
        requestAnimationFrame(() => {
          if (el) el.scrollTop = el.scrollHeight;
        });
      }
    }
  }
};

// 监听滚动加载更多
const handleScroll = (e) => {
  const el = e.target;
  // 触顶 && 还有更多 && 没在加载
  if (el.scrollTop < 20 && chatStore.hasMore && !chatStore.isLoadingMore) {
    // 记录加载前的高度
    const oldHeight = el.scrollHeight;

    // 触发加载
    chatStore.loadMoreHistory();

    // 加载完成后恢复位置
    // 我们需要监听 messages 长度变化来执行恢复
    const unwatch = watch(() => chatStore.messages.length, async () => {
      await nextTick();
      const newHeight = el.scrollHeight;
      el.scrollTop = newHeight - oldHeight; // 保持视口停留在原来的消息处
      unwatch(); // 仅执行一次
    });
  }
};

// === 7. 交互动作 ===
// 标记：已发送消息，等这条消息真正渲染（服务器回声）后强制滚到底部，
// 避免固定 100ms 提前滚动导致停在旧位置
let pendingScrollToBottom = false;

const send = (event) => {
  if (event?.isComposing) return;
  const text = inputContent.value.trim();
  if (!text) return;

  if (userStore.isGuest) {
    error('请先设置名字后再参与聊天');
    userStore.showNameModal = true;
    return;
  }

  pendingScrollToBottom = true;
  // Start clipboard access in this click/Enter gesture, before awaiting the API.
  const command = parseChatCommand(text);
  if (command?.name === 'code' && command.valid && command.parameter === 'copy') chatStore.copyPairingCode();
  else if (isShareCommand(text)) chatStore.runShareCommand(command?.parameter, command?.valid === true);
  else playerStore.sendChatMessage(text);
  inputContent.value = '';
};

// 监听：打开窗口或切换 Tab 时滚到底部
watch([() => chatStore.isOpen, activeTab], async ([isOpen]) => {
  showCommands.value = false;
  if (isOpen) {
    chatStore.unreadCount = 0; // 只要打开就清空未读
    await scrollToBottom(true);
  }
});

// 监听：收到新消息时 (且在当前Tab)，尝试滚到底部
watch(() => processedMessages.value.length, (newLen, oldLen) => {
  // 如果是增量追加(正常聊天)，且在底部，则自动滚
  // 如果是历史加载(头部追加)，则不由这里处理(由handleScroll处理)
  if (newLen > oldLen) {
    if (pendingScrollToBottom) {
      // 刚发过消息：强制滚到自己刚发送的那条
      pendingScrollToBottom = false;
      scrollToBottom(true);
    } else {
      scrollToBottom(false);
    }
  }
});
</script>

<style scoped>
.chat-scroll::-webkit-scrollbar {
  width: 4px;
}
.chat-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.chat-scroll::-webkit-scrollbar-thumb {
  @apply bg-accent/20 rounded;
}
.chat-scroll::-webkit-scrollbar-thumb:hover {
  @apply bg-accent/50;
}
</style>
