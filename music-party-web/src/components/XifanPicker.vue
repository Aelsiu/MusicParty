<script setup>
import { importStatusLabels } from '../composables/useVideoFavoriteImport';
defineProps({search:Object,queue:Array});
</script>
<template>
  <section class="xifan-picker" role="dialog" aria-label="选择 XIFAN 剧集和线路">
    <header><h3>{{search.flow.preview.value.anime.title}}</h3><button @click="search.picker.value=false">关闭</button></header>
    <label v-if="search.flow.preview.value.sources.length">线路 <select :value="search.flow.preview.value.selectedSourceId" :disabled="search.busy.value||search.flow.running.value" @change="search.preview(search.flow.preview.value.anime.id,Number($event.target.value))"><option v-for="source in search.flow.preview.value.sources" :key="source.id" :value="source.id">{{source.name}} · 待验证</option></select></label>
    <p v-if="search.flow.items.value.length">仅确认选中剧集的 MP4 时长；线路尚未通过持续实播验收。</p>
    <p v-else>{{search.flow.preview.value.sources.length?'该线路暂无可选剧集':'该作品暂无可选剧集'}}</p>
    <div class="episodes"><label v-for="item in search.flow.items.value" :key="item.itemId"><input type="checkbox" :checked="search.flow.selected.value[item.itemId]!==undefined" :disabled="search.busy.value||search.flow.running.value||queue.some(row=>row.music.id===item.itemId)||!search.flow.retryable(item.itemId)" @change="search.flow.toggle(item,$event.target.checked)" /><span>{{item.kind}} {{item.episodeNumber}} · {{item.title}}<small>#{{item.episodeId}} · 入队前确认时长<span v-if="search.flow.result.value?.items?.find(row=>row.itemId===item.itemId)"> · {{importStatusLabels[search.flow.result.value.items.find(row=>row.itemId===item.itemId).status]||'结果待确认'}}</span></small></span></label></div>
    <p v-if="search.flow.error.value||search.error.value" role="alert">{{search.flow.error.value||search.error.value}}</p>
    <p v-if="search.flow.uncertain.value">结果暂无法确认，请先核对队列 <button @click="search.flow.query">查询原结果</button></p>
    <footer><span>已选 {{search.flow.selection.value.length}} / 上限 {{search.flow.batchLimit.value}}</span><button @click="search.flow.submit" :disabled="search.busy.value||search.flow.running.value||!search.flow.selection.value.length||search.flow.selection.value.length>search.flow.batchLimit.value">{{search.flow.running.value?'正在确认结果':'添加选中剧集'}}</button></footer>
  </section>
</template>
<style scoped>
.xifan-picker{position:absolute;inset:0;z-index:55;display:flex;flex-direction:column;padding:16px;gap:12px;background:rgb(var(--medical-50));min-height:0}header,footer{display:flex;justify-content:space-between;gap:12px}button{padding:8px;color:rgb(var(--accent))}select{max-width:100%;background:rgb(var(--medical-100));padding:8px}.episodes{overflow:auto;flex:1;min-height:0}.episodes label{display:flex;align-items:center;gap:12px;padding:12px;border-bottom:1px solid rgb(var(--medical-200))}small{display:block;opacity:.65;font-size:11px}p{font-size:12px}
</style>
