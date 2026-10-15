<template>
  <div class="episode-overlay" role="dialog" aria-modal="true" aria-labelledby="episode-title">
    <section class="episode-dialog">
      <header><h3 id="episode-title">{{ single ? '确认分集' : '选择分集' }}</h3><button type="button" aria-label="关闭分集选择" @click="$emit('close')">×</button></header>
      <div class="episode-summary"><CoverImage :src="flow.preview.value.season.cover" :scanline="false" class="episode-cover" /><div class="min-w-0 flex-1"><OverflowText as="p" :text="flow.preview.value.season.title" /><small>返回 {{ flow.items.value.length }} 集 · 本批上限 {{ flow.batchLimit.value }}<span v-if="flow.preview.value.season.truncated"> · 列表已截断</span></small></div></div>
      <label class="episode-tools"><input type="checkbox" :checked="all" :disabled="flow.running.value || !eligible.length" @change="selectAll($event.target.checked)" />全选当前可选集 · 已选 {{ flow.selection.value.length }}</label>
      <div class="episode-list">
        <label v-for="ep in flow.items.value" :key="ep.itemId" class="episode-row">
          <input type="checkbox" :checked="flow.selected.value[ep.itemId] !== undefined" :disabled="flow.running.value || !selectable(ep)" @change="flow.toggle(ep,$event.target.checked)" />
          <span class="flex-1 min-w-0"><OverflowText :text="`${ep.episodeTitle} · ${ep.title}`"/><small>{{ Math.ceil(ep.durationMs / 60000) }}分钟 · {{ queued(ep) ? '已在队列' : ep.reasonCode ? labels[ep.reasonCode] || '当前不可选' : '待播放核验' }}<span v-if="resultFor(ep)"> · {{ labels[resultFor(ep).status] || '结果待确认' }}</span></small></span>
        </label>
      </div>
      <footer>
        <p v-if="flow.selection.value.length > flow.batchLimit.value" role="alert">本批上限 {{ flow.batchLimit.value }} 项，请减少选择</p>
        <p v-if="flow.result.value?.kind === 'IMPORT_ERROR' || flow.error.value" role="alert">{{ flow.result.value?.message || flow.error.value }}</p>
        <p v-if="flow.uncertain.value" role="status">结果暂无法确认，请先核对待播队列</p>
        <button v-if="flow.uncertain.value" @click="flow.acknowledgeQueueCheck">已核对队列，重新预览</button>
        <button @click="flow.submit" :disabled="flow.running.value || !flow.selection.value.length || flow.selection.value.length > flow.batchLimit.value">{{ flow.running.value ? '正在确认结果' : `添加选中 ${flow.selection.value.length} 集` }}</button>
      </footer>
    </section>
  </div>
</template>
<script setup>
import { computed } from 'vue';
import OverflowText from './OverflowText.vue';
import CoverImage from './CoverImage.vue';
import { importStatusLabels as labels } from '../composables/useVideoFavoriteImport';
const props=defineProps({flow:Object,queue:Array,single:Boolean});
defineEmits(['close']);
const queued=ep => props.queue.some(item => item.music.id===ep.canonicalId);
const selectable=ep => ep.selectable && !queued(ep) && props.flow.retryable(ep.itemId);
const eligible=computed(()=>props.flow.items.value.filter(selectable));
const all=computed(()=>eligible.value.length>0 && eligible.value.every(ep=>props.flow.selected.value[ep.itemId]!==undefined));
const resultFor=ep => props.flow.result.value?.items?.find(item=>item.itemId===ep.itemId);
const selectAll=checked => { if(!props.flow.running.value) props.flow.selected.value=checked?Object.fromEntries(eligible.value.map(ep=>[ep.itemId,0])):{}; };
</script>
<style scoped>
.episode-overlay{position:absolute;inset:0;z-index:30;display:flex;align-items:center;justify-content:center;background:#0008;padding:12px}.episode-dialog{background:rgb(var(--surface));color:rgb(var(--medical-800));width:min(620px,100%);max-height:100%;display:flex;flex-direction:column;border:1px solid #888;overflow:hidden}.episode-dialog header,.episode-dialog footer{display:flex;gap:12px;padding:12px;flex-shrink:0}.episode-dialog header{justify-content:space-between}.episode-dialog h3,.episode-dialog p{margin:0}.episode-dialog footer{flex-direction:column}.episode-dialog button{cursor:pointer;border:1px solid #888;padding:8px}.episode-dialog button:disabled{opacity:.5;cursor:default}.episode-summary{display:flex;gap:12px;padding:12px;align-items:center}.episode-cover{width:48px;height:64px;flex-shrink:0}.episode-tools{padding:8px 12px;font-size:12px}.episode-list{overflow:auto;overscroll-behavior:contain;min-height:0}.episode-row{display:flex;gap:12px;align-items:center;padding:12px;border-top:1px solid #8884}.episode-row>span{min-width:0;overflow-wrap:anywhere}.episode-row small{display:block;opacity:.7}.episode-dialog small,.episode-dialog footer p{font-size:12px}
</style>
