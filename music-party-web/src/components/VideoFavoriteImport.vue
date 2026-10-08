<template>
  <div class="flex-1 min-h-0 overflow-y-auto p-4 space-y-4">
    <p class="text-xs text-medical-500 break-all">支持 HTTPS 链接：space.bilibili.com/数字UID/favlist?fid=收藏夹ID 或 www.bilibili.com/medialist/play/数字UID?business=space&amp;business_id=收藏夹ID。不支持短链接。不自动导入其余 P。</p>
    <form @submit.prevent="flow.load(1)" class="flex gap-2"><input v-model="flow.link.value" aria-label="B站收藏夹链接" placeholder="粘贴 B站收藏夹链接" class="min-w-0 flex-1 border border-medical-200 p-2 bg-surface text-sm" /><button :disabled="flow.busy.value || flow.running.value" class="bg-accent text-white p-2 text-xs disabled:opacity-40">预览</button></form>
    <p v-if="flow.error.value" role="alert" class="text-xs text-red-500">{{ flow.error.value }}</p>
    <template v-if="flow.preview.value">
      <h3 class="font-bold text-sm">{{ flow.preview.value.title }}</h3>
      <p class="text-xs text-medical-500">每条默认 P1；聚合时长仅供参考，提交时重新校验。预览有效期至 {{ new Date(flow.preview.value.expiresAt).toLocaleTimeString() }}。</p>
      <div class="flex gap-3 flex-wrap text-xs"><button @click="flow.selectLoaded" :disabled="flow.running.value" class="text-accent">全选已加载可选项</button><span>已选 {{ flow.selection.value.length }} 条</span><span>可用配额约 {{ flow.available.value }} · 本批上限 {{ flow.batchLimit.value }}</span></div>
      <div v-for="item in flow.items.value" :key="item.itemId" class="border border-medical-200 bg-surface p-3 space-y-2">
        <div class="flex gap-2 items-center"><input type="checkbox" :aria-label="`选择 ${item.title}`" :checked="flow.selected.value[item.itemId] !== undefined" :disabled="!item.selectable || !flow.retryable(item.itemId) || flow.running.value" @change="flow.toggle(item, $event.target.checked)" /><CoverImage :src="item.cover" class="w-10 h-10 shrink-0" :scanline="false" /><div class="flex-1 min-w-0"><p class="font-bold text-sm truncate">{{ item.title }}</p><p class="text-xs text-medical-500">{{ item.ownerName }} · {{ item.selectable ? '提交时重新校验' : reason(item.reasonCode) }}</p></div></div>
        <button v-if="item.selectable && !flow.descriptors.value[item.itemId]" @click="flow.metadata(item)" class="text-xs text-accent">展开分 P（当前 P1）</button>
        <select v-if="flow.descriptors.value[item.itemId]" :value="flow.selected.value[item.itemId] ?? 1" @change="flow.selected.value = { ...flow.selected.value, [item.itemId]: Number($event.target.value) }" :disabled="flow.running.value || !flow.retryable(item.itemId)" :aria-label="`选择 ${item.title} 的 P`" class="w-full p-2 text-xs border border-medical-200 bg-medical-50"><option v-for="p in flow.descriptors.value[item.itemId].pages" :key="p.page" :value="p.page" :disabled="!p.withinDurationLimit">P{{ p.page }} · {{ p.title }} · {{ Math.ceil(p.durationMs / 60000) }}分钟{{ p.withinDurationLimit ? '' : '（超出上限）' }}</option></select>
      </div>
      <button v-if="flow.preview.value.hasMore" @click="flow.load(flow.preview.value.nextPage || flow.preview.value.page + 1)" :disabled="flow.busy.value" class="w-full p-3 text-accent text-xs">加载下一页</button>
      <p v-if="flow.preview.value.truncated" class="text-xs text-medical-500">本次只浏览前 500 条候选。</p>
      <p v-if="flow.selection.value.length > flow.batchLimit.value" role="alert" class="text-xs text-red-500">选择超过本批上限，请缩小选择。</p>
      <button @click="flow.submit" :disabled="flow.running.value || flow.busy.value || !flow.selection.value.length || flow.selection.value.length > flow.batchLimit.value" class="w-full bg-accent text-white p-3 text-sm disabled:opacity-40">提交 {{ flow.selection.value.length }} 条（每条一个 P）</button>
    </template>
    <section v-if="flow.running.value || flow.result.value" class="border border-medical-200 p-3 space-y-2" aria-label="最近导入结果">
      <p class="text-sm font-bold">{{ flow.running.value ? '结果待确认，请先查询原批次' : '最近批次结果' }}</p><button @click="flow.query" class="text-xs text-accent p-2">查询原批次</button>
      <p v-if="flow.result.value?.resultExpiresAt" class="text-xs text-medical-500">结果保存至 {{ new Date(flow.result.value.resultExpiresAt).toLocaleTimeString() }}；过期后先核对队列，不自动重新导入整批。</p>
      <button v-if="flow.uncertain.value" @click="flow.acknowledgeQueueCheck" class="border border-medical-200 p-2 text-xs text-accent">已核对待播队列，重新预览并选择</button>
      <p v-if="flow.result.value?.counts" class="text-xs">已入队 {{ flow.result.value.counts.added }} / {{ flow.result.value.counts.requested }} · 跳过 {{ flow.result.value.counts.skipped }} · 失败 {{ flow.result.value.counts.failed }} · 取消 {{ flow.result.value.counts.cancelled }}</p>
      <p v-if="flow.result.value?.kind === 'IMPORT_ERROR'" class="text-xs text-red-500">{{ flow.result.value.message || flow.result.value.error?.message || '结果不可用，请先核对待播队列，不要自动重发整批' }}</p>
      <p v-for="item in flow.result.value?.items || []" :key="item.itemId" class="text-xs">{{ flow.items.value.find(x => x.itemId === item.itemId)?.title || item.canonicalId || item.itemId }}：{{ reason(item.status) }}{{ item.retryable ? '（可重新选择失败项）' : '' }}</p>
    </section>
  </div>
</template>
<script setup>
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useVideoFavoriteImport, importStatusLabels } from '../composables/useVideoFavoriteImport';
import CoverImage from './CoverImage.vue';
const flow = useVideoFavoriteImport(usePlayerStore(), useUserStore());
const reason = code => importStatusLabels[code] || ({ UNSUPPORTED_RESOURCE: '不支持的资源', INVALID_RESOURCE: '内容已失效', ACCESS_DENIED: '无访问权限' }[code]) || '内容或来源不可用';
</script>
