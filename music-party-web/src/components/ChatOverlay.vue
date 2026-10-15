<template>
  <!--
    外层容器
    pointer-events-none: 确保透明区域不挡住下面内容的点击
    z-[100]: 确保在大多数内容之上
  -->
  <div class="sidebar-chat">

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
          class="chat-records pointer-events-auto bg-surface border border-medical-200 shadow-2xl flex flex-col overflow-hidden"

          @mousedown.stop
          @touchstart.stop
      >
        <!--
           1. Header (聊天记录)
           固定在侧栏内
        -->
        <div


            class="h-10 bg-medical-50 border-b border-medical-200 flex items-center justify-between px-3 flex-shrink-0 select-none"

        >
          <div class="font-mono text-xs font-bold text-medical-500 flex items-center gap-2">
            <MessageSquare class="w-3 h-3"/> CHAT
          </div>
          <button @click="toggleChat" class="text-medical-400 hover:text-medical-900 p-1 cursor-pointer">
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

      </div>
    </Transition>
    <div class="chat-input-area bg-surface border-t border-medical-200">
      <button id="tutorial-chat" data-guide="chat" ref="expandButton" @click="toggleChat" class="chat-expand w-full flex items-center justify-center text-medical-400" :class="{'has-unread':chatStore.hasUnread && visible && documentVisible}" :aria-expanded="chatStore.isOpen" :aria-label="`${chatStore.isOpen?'收起':'展开'}聊天，${chatStore.hasUnread?'有':'无'}未读消息`"><ChevronDown v-if="chatStore.isOpen" class="w-3.5 h-3.5"/><ChevronUp v-else class="w-3.5 h-3.5"/></button>
        <!-- 4. Input Area (仅在 Chat Tab 显示) -->
        <div ref="inputAreaRef" class="chat-input-row relative px-2 bg-surface border-t border-medical-200 flex gap-2 flex-shrink-0">
          <div v-if="showCommands" id="chat-command-menu" role="menu" aria-label="快捷指令" class="absolute bottom-full left-2 right-2 mb-1 max-h-64 overflow-y-auto bg-surface border border-medical-200 shadow-xl z-10 p-1">
            <button v-for="command in availableCommands" :key="command.text" type="button" role="menuitem" @click="chooseCommand(command.text)" class="w-full px-2 py-2 flex items-center justify-between gap-2 text-left hover:bg-medical-50 focus-visible:bg-medical-50">
              <span class="font-mono text-xs text-accent whitespace-nowrap">{{ command.text }}</span><span class="text-[10px] text-medical-500">{{ command.label }}</span>
            </button>
          </div>
          <button type="button" @click="showCommands=!showCommands" aria-label="快捷指令" aria-haspopup="menu" aria-controls="chat-command-menu" :aria-expanded="showCommands" class="chat-command-button text-medical-400 hover:text-accent"><MoreVertical class="w-4 h-4" /></button>
          <input
              ref="inputRef"
              v-model="inputContent"
              @keydown.enter="send"
              @compositionstart="composing=true" @compositionend="compositionEnd"
              @keydown.tab="completeInput"

              @mousedown.stop
              @touchstart.stop
              placeholder="TYPE MESSAGE..."
              aria-label="聊天消息"
              class="min-w-0 flex-1 bg-medical-50 border border-medical-200 px-2 py-1.5 text-xs outline-none focus:border-accent font-sans transition-colors rounded-sm text-medical-900"
          />
          <button
              @click="send" aria-label="发送消息"
              class="bg-accent hover:bg-accent-hover text-white px-3 py-1.5 transition-colors rounded-sm flex items-center justify-center shadow-sm shadow-accent/20"
          >
            <Send class="w-4 h-4" />
          </button>
        </div>

    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, computed, onBeforeUnmount } from 'vue';
import { useChatStore } from '../stores/chat';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useToast } from '../composables/useToast';
import { useEventListener, onClickOutside } from '@vueuse/core';
import { MessageSquare, X, Send, Terminal, Zap, Loader2, MoreVertical, ChevronUp, ChevronDown } from 'lucide-vue-next';
import dayjs from 'dayjs';
import { commandChoices, completeCommand } from '../utils/chatCommands.js';
import { roomSession } from '../services/roomSession';
import { submitChatMessage } from '../services/chatSubmission.js';
import { registerBackHandler } from '../services/backNavigation';
const props=defineProps({visible:Boolean});

const chatStore = useChatStore();
const playerStore = usePlayerStore();
const userStore = useUserStore();
const { success, error } = useToast();
const inputContent = computed({get:()=>chatStore.draft,set:value=>chatStore.draft=value});
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
const activeTab=computed({get:()=>chatStore.activeTab,set:value=>chatStore.activeTab=value});
const expandButton=ref(null),documentVisible=ref(!document.hidden);
let composing=false,compositionEndedAt=-Infinity,alive=true,historyWatch=null;
function compositionEnd(){composing=false;compositionEndedAt=performance.now();}
const chatVisible=computed(()=>props.visible&&userStore.isAuthPassed&&chatStore.isOpen&&activeTab.value==='CHAT'&&documentVisible.value);
function toggleChat(){showCommands.value=false;chatStore.toggleChat();if(!chatStore.isOpen)nextTick(()=>expandButton.value?.focus?.());}
watch(chatVisible,async value=>{chatStore.setVisible(false);const generation=roomSession.generation;await nextTick();if(alive&&generation===roomSession.generation&&value&&chatVisible.value){chatStore.setVisible(true);chatStore.markRead();}},{immediate:true,flush:'sync'});
useEventListener(document,'visibilitychange',()=>{documentVisible.value=!document.hidden;});
watch(()=>props.visible,value=>{if(!value){showCommands.value=false;composing=false;}});
const unregisterBack=registerBackHandler(90,()=>{if(!props.visible)return false;if(showCommands.value){showCommands.value=false;return true;}if(chatStore.isOpen){toggleChat();return true;}return false;});
watch(()=>roomSession.generation,()=>{showCommands.value=false;pendingScrollToBottom=false;historyWatch?.();historyWatch=null;});
onBeforeUnmount(()=>{alive=false;historyWatch?.();chatStore.setVisible(false);unregisterBack();});
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
    const oldHeight = el.scrollHeight,oldTop=el.scrollTop,generation=roomSession.generation;

    // 触发加载
    chatStore.loadMoreHistory();

    historyWatch?.();
    historyWatch=watch(()=>chatStore.isLoadingMore,async loading=>{
      if(loading)return;
      historyWatch?.();historyWatch=null;await nextTick();
      if(alive&&generation===roomSession.generation&&msgListRef.value===el)el.scrollTop=oldTop+el.scrollHeight-oldHeight;
    },{flush:'post'});
  }
};

// === 7. 交互动作 ===
// 标记：已发送消息，等这条消息真正渲染（服务器回声）后强制滚到底部，
// 避免固定 100ms 提前滚动导致停在旧位置
let pendingScrollToBottom = false;

const send = (event) => {
  if (composing || event?.isComposing || event?.keyCode===229 || event?.type==='keydown'&&performance.now()-compositionEndedAt<30) return;
  event?.preventDefault?.();
  const text = inputContent.value.trim();
  if (!text) return;

  if (/^\/\/(help|public|private)(?:\s|$)/i.test(text)) {
    submitChatMessage(text, { chat: chatStore, send: playerStore.sendChatMessage, manager: roomSession.ownerAccess });
    inputContent.value = '';
    return;
  }
  if (userStore.isGuest) {
    error('请先设置名字后再参与聊天');
    userStore.showNameModal = true;
    return;
  }

  pendingScrollToBottom = true;
  // Start clipboard access in this click/Enter gesture, before awaiting the API.
  submitChatMessage(text, { chat: chatStore, send: playerStore.sendChatMessage });
  inputContent.value = '';
};

// 监听：打开窗口或切换 Tab 时滚到底部
watch([() => chatStore.isOpen, activeTab], async ([isOpen]) => {
  showCommands.value = false;
  if (isOpen) {
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
.sidebar-chat{position:absolute;inset:0;pointer-events:none;--chat-input-height:72px}
.chat-records{position:absolute;left:0;right:0;bottom:var(--chat-input-height);height:calc((100% - var(--chat-input-height))*.78);min-height:0;z-index:2}
.chat-input-area{position:absolute;bottom:0;left:0;right:0;height:var(--chat-input-height);pointer-events:auto;z-index:3;box-sizing:border-box}
.chat-expand{height:16px}.chat-input-row{height:55px;align-items:center}.chat-command-button{width:14px;height:36px;display:grid;place-items:center;padding:0}
.chat-input-row input,.chat-input-row>button:last-child{height:36px}
.has-unread{animation:chat-unread 2.4s ease-in-out infinite}@keyframes chat-unread{0%,100%{color:rgb(var(--medical-400));opacity:.65}50%{color:rgb(var(--accent));opacity:1}}
@media(max-width:767px){.sidebar-chat{--chat-input-height:calc(76px + env(safe-area-inset-bottom))}.chat-expand{height:20px}.chat-command-button{width:18px;height:44px}.chat-input-area{padding-bottom:env(safe-area-inset-bottom)}}
@media(prefers-reduced-motion:reduce){.has-unread{animation:none;color:rgb(var(--accent))}}

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
