<template>
  <div class="flex-1 min-h-0 overflow-y-auto p-4 space-y-4">
    <form v-if="!initialPreview" @submit.prevent="flow.load(1)" class="flex gap-2"><input v-model="flow.link.value" aria-label="B站收藏夹链接" placeholder="粘贴 B站收藏夹链接" class="min-w-0 flex-1 border border-medical-200 p-2 bg-surface text-sm" /><button :disabled="flow.busy.value || flow.running.value" class="bg-accent text-medical-50 p-2 text-xs disabled:opacity-40">预览</button></form>
    <template v-if="flow.preview.value">
      <h3 class="font-bold text-sm">{{ flow.preview.value.title }}</h3>
      <div class="flex gap-3 flex-wrap text-xs"><button @click="flow.selectLoaded" :disabled="flow.running.value" class="text-accent">全选已加载可选项</button><span>已选 {{ flow.selection.value.length }} 条</span><span>可用配额约 {{ flow.available.value }} · 本批上限 {{ flow.batchLimit.value }}</span></div>
      <div v-for="item in flow.items.value" :key="item.itemId" class="border border-medical-200 bg-surface p-3 space-y-2">
        <div class="flex gap-2 items-center"><input type="checkbox" :aria-label="`选择 ${item.title}`" :checked="flow.selected.value[item.itemId] !== undefined" :disabled="!item.selectable || !flow.retryable(item.itemId) || flow.running.value" @change="flow.toggle(item, $event.target.checked)" /><CoverImage :src="item.cover" class="w-10 h-10 shrink-0" :scanline="false" /><div class="flex-1 min-w-0"><OverflowText as="p" class="font-bold text-sm " :text="String(item.title)" /><p class="text-xs text-medical-500">{{ item.ownerName }} · {{ item.selectable ? '提交时重新校验' : reason(item.reasonCode) }}</p></div></div>
        <button v-if="item.selectable && !flow.descriptors.value[item.itemId]" @click="flow.metadata(item)" class="text-xs text-accent">展开分 P（当前 P1）</button>
        <select v-if="flow.descriptors.value[item.itemId]" :value="flow.selected.value[item.itemId] ?? 1" @change="flow.selected.value = { ...flow.selected.value, [item.itemId]: Number($event.target.value) }" :disabled="flow.running.value || !flow.retryable(item.itemId)" :aria-label="`选择 ${item.title} 的 P`" class="w-full p-2 text-xs border border-medical-200 bg-medical-50"><option v-for="p in flow.descriptors.value[item.itemId].pages" :key="p.page" :value="p.page" :disabled="!p.withinDurationLimit">P{{ p.page }} · {{ p.title }} · {{ Math.ceil(p.durationMs / 60000) }}分钟{{ p.withinDurationLimit ? '' : '（超出上限）' }}</option></select>
      </div>
      <button v-if="flow.preview.value.hasMore" @click="flow.load(flow.preview.value.nextPage || flow.preview.value.page + 1)" :disabled="flow.busy.value" class="w-full p-3 text-accent text-xs">加载下一页</button>
      <button @click="flow.submit" :disabled="flow.running.value || flow.busy.value || !flow.selection.value.length || flow.selection.value.length > flow.batchLimit.value" class="w-full bg-accent text-white p-3 text-sm disabled:opacity-40">提交 {{ flow.selection.value.length }} 条（每条一个 P）</button>
    </template>
    <button v-if="flow.uncertain.value" @click="flow.acknowledgeQueueCheck" class="text-xs text-accent">已核对待播队列，重新预览并选择</button>
  </div>
</template>
<script setup>
import OverflowText from './OverflowText.vue';
import { watch,inject } from 'vue';
import {searchFeedbackKey} from '../composables/useSearchFeedback';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useVideoFavoriteImport, importStatusLabels } from '../composables/useVideoFavoriteImport';
import CoverImage from './CoverImage.vue';
const emit = defineEmits(['complete']);
const flow = useVideoFavoriteImport(usePlayerStore(), useUserStore(),'FAVORITES',inject(searchFeedbackKey,null));
const props = defineProps({ initialPreview: Object });
watch(() => props.initialPreview, value => { if (value) flow.acceptPreview(value); }, { immediate: true });
watch(flow.result, value => {
    if (flow.preview.value?.previewId === flow.submittedPreviewId.value && value?.status === 'COMPLETED' && value.items?.length && value.items.every(item => item.status === 'ADDED')) emit('complete');
});
const reason = code => importStatusLabels[code] || ({ UNSUPPORTED_RESOURCE: '不支持的资源', INVALID_RESOURCE: '内容已失效', ACCESS_DENIED: '无访问权限' }[code]) || '内容或来源不可用';
</script>
