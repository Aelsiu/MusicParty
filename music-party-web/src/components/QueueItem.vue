<template>
  <div
      class="group relative flex items-center gap-2 p-2 border hover:border-medical-300 transition-all mb-2 h-14"
      :class="isFmMarker ? 'bg-accent/5 border-accent/30' : 'bg-surface border-medical-100'"
  >
    <!-- 序号 -->
    <div v-if="index !== undefined" class="w-6 text-center font-mono text-xs text-medical-400">{{ String(index + 1).padStart(2, '0') }}</div>
    <div v-else class="w-6 text-center font-mono text-xs text-medical-400">#</div>

    <div class="flex-1 min-w-0">
      <div class="flex items-center min-w-0">
        <OverflowText as="div" class="text-sm font-bold text-medical-800 " :text="String(item.music.name)" />
        <span v-if="isFmMarker" class="ml-1 flex-shrink-0 px-1 bg-accent text-white text-[8px] font-mono font-bold">FM</span>
      </div>
      <div class="flex justify-between items-center">
        <OverflowText v-if="!isPending && (!item.status || item.status === 'READY')" class="text-xs text-medical-500" :text="`${item.music.artists[0] || ''}${isVideo ? ` · ${item.music.platform==='xifan-video' ? 'XIFAN' : item.music.id.startsWith('bilibili:ep') ? '番剧' : `P${item.music.id.split(':p')[1]}`} · ${Math.ceil(item.music.duration / 60000)}分钟` : ''}`" />

        <!-- 下载中状态：显示闪烁的 LOADING -->
        <div v-else-if="isPending || item.status === 'DOWNLOADING' || item.status === 'PENDING'" class="text-xs font-mono font-bold text-accent animate-pulse flex items-center gap-1">
          <Loader2 class="w-3 h-3 animate-spin" /> {{ isVideo ? (player.sourceStatus === 'ERROR' && isPending ? '来源失败，可重试' : '正在解析') : 'LOADING...' }}
        </div>

        <!-- 失败状态 -->
        <div v-else-if="item.status === 'FAILED'" class="text-xs font-mono font-bold text-red-500">
          {{ isVideo ? '来源失败，可重试' : 'DOWNLOAD FAILED' }}
        </div>
        <div class="text-[10px] text-medical-300 bg-medical-50 px-1 border border-medical-100">
          {{ userStore.resolveName(item.enqueuedBy.token, item.enqueuedBy.name) }}
        </div>
      </div>
    </div>

    <!-- 操作遮罩 -->
    <div v-if="!userStore.isGuest && !isFmMarker" class="absolute inset-y-0 right-0 bg-surface/90 px-2 flex items-center gap-2 opacity-100 md:opacity-0 md:group-hover:opacity-100 md:focus-within:opacity-100 transition-opacity">
      <button @click="player.topSong(item.queueId)" :disabled="isPending || !!player.cancelTopPending[item.queueId] || item.priority === 'GLOBAL_TOP'" :title="topTitle" :aria-label="topTitle" class="p-1 hover:text-accent"><ArrowUpToLine class="w-4 h-4"/></button>
      <button v-if="player.queueFeatures.cancelTop && item.priority && item.priority !== 'REGULAR'" @click="player.cancelTop(item.queueId)" :disabled="isPending || !player.cancelTopReady || !!player.cancelTopPending[item.queueId]" title="取消置顶" aria-label="取消置顶" class="p-1 hover:text-accent"><ArrowDownToLine class="w-4 h-4" /></button>
      <button v-if="canRemove" @click="player.removeSong(item.queueId)" title="Remove" :aria-label="isVideo ? '删除待播视频' : '删除待播歌曲'" class="p-1 hover:text-red-500"><Trash2 class="w-4 h-4"/></button>
    </div>

    <!-- 标记置顶的歌曲 -->
    <div v-if="item.priority === 'GLOBAL_TOP'" class="absolute top-0 right-0 w-2 h-2 bg-accent" title="全局置顶"></div>
    <div v-else-if="item.priority === 'USER_TOP'" class="absolute top-0 right-0 w-3 h-3 bg-accent [clip-path:polygon(100%_0,0_0,100%_100%)]" title="个人优先"></div>
  </div>
</template>

<script setup>
import OverflowText from './OverflowText.vue';
import { computed } from 'vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { Trash2, ArrowUpToLine, ArrowDownToLine, Loader2 } from 'lucide-vue-next';
import { roomSession } from '../services/roomSession';

const props = defineProps({
  item: {
    type: Object,
    required: true
  },
  index: {
    type: Number,
    default: undefined
  }
});

const player = usePlayerStore();
const userStore = useUserStore();
const isVideo = computed(() => player.mediaMode === 'VIDEO');
const isPending = computed(() => player.queuePendingId === props.item.queueId || isVideo.value && player.videoPending?.origin === 'QUEUE' && player.videoPending?.queueId === props.item.queueId);
const topTitle = computed(() => isPending.value ? '已进入播放准备' : props.item.priority === 'GLOBAL_TOP' ? '已全局置顶' : props.item.priority === 'USER_TOP' ? '升级为全局置顶' : '置顶');
const isFmMarker = computed(() => props.item.music.platform === 'netease-fm');
const canRemove = computed(() => roomSession.ownerAccess || props.item.enqueuedBy.token === userStore.userToken);
</script>
