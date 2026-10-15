<template>
  <div class="video-search-viewport">
    <div class="video-search-track" :class="{selected:selectionOpen}">
      <section ref="resultsElement" class="video-candidates" :inert="selectionOpen" aria-label="视频候选">
        <div class="candidate-heading"><span>{{platform==='xifan'?'番剧候选':'视频候选'}}</span><div class="candidate-actions"><span>{{candidates.length}} 项</span><button type="button" :aria-label="ui.videoSearchLayout==='compact'?'切换为封面网格':'切换为横向短卡'" :title="ui.videoSearchLayout==='compact'?'封面网格':'横向短卡'" @click="ui.setVideoSearchLayout(ui.videoSearchLayout==='compact'?'poster':'compact')"><LayoutGrid v-if="ui.videoSearchLayout==='compact'"/><Rows2 v-else/></button></div></div>
        <div class="candidate-grid" :class="[ui.videoSearchLayout,{bilibili:platform==='bilibili'}]">
          <button v-for="item in candidates" :key="item.id" type="button" class="candidate-card" :aria-label="`选择 ${item.title}`" @click="choose(item)">
            <CoverImage :src="item.cover" :scanline="false" class="candidate-cover"/>
            <div class="candidate-copy"><OverflowText class="candidate-title" :text="item.title"/><div class="candidate-meta"><span>{{item.year}}</span><span>{{item.summary}}</span></div></div>
          </button>
        </div>
        <button v-if="platform==='xifan'&&xifan.hasMore.value" class="more-candidates" :disabled="xifan.busy.value" @click="xifan.search(xifan.page.value+1)">加载下一页</button>
      </section>
      <section class="video-selection" :inert="!selectionOpen" aria-label="视频选择">
        <header class="selection-heading"><CoverImage :src="selectionCover" :scanline="false" class="selection-cover"/><div class="selection-heading-copy"><button type="button" class="selection-back" @click="back" aria-label="返回视频候选"><ArrowLeft/><OverflowText :text="selectionTitle"/></button><span class="selection-kicker">{{platform==='xifan'?'XIFAN / EPISODES':kind==='favorites'?'BILIBILI / FAVORITES':kind==='pgc'?'BILIBILI / EPISODES':'BILIBILI / PARTS'}}</span></div></header>
        <VideoFavoriteImport v-if="kind==='favorites'&&favorites" :initial-preview="favorites"/>
        <template v-else-if="activeFlow.preview.value&&!selectionLoading">
          <label v-if="kind==='xifan'&&xifan.flow.preview.value.sources?.length" class="source-field">线路<select :value="xifan.flow.preview.value.selectedSourceId" :disabled="xifan.busy.value||activeFlow.running.value" @change="xifan.preview(xifan.flow.preview.value.anime.id,Number($event.target.value))"><option v-for="source in xifan.flow.preview.value.sources" :key="source.id" :value="source.id">{{source.name}}</option></select></label>
          <div class="selection-tools"><label><input type="checkbox" :checked="allSelected" :indeterminate.prop="someSelected&&!allSelected" :disabled="legacyQueueing||activeFlow.running.value||xifan.busy.value||selectionLoading||!eligible.length" @change="selectAll($event.target.checked)">全选</label><span>已选 {{activeFlow.selection.value.length}} / 上限 {{selectionLimit}}</span></div>
          <div class="selection-episodes">
            <label v-for="row in rows" :key="row.itemId" class="selection-episode" :class="{unavailable:!selectable(row),checked:activeFlow.selected.value[row.itemId]!==undefined}">
              <input type="checkbox" :checked="activeFlow.selected.value[row.itemId]!==undefined" :disabled="legacyQueueing||activeFlow.running.value||xifan.busy.value||selectionLoading||!selectable(row)" @change="toggle(row,$event.target.checked)">
              <span class="episode-code">{{queued(row)?'已入队':row.index}}</span><OverflowText class="episode-title" :text="row.title"/>
            </label>
          </div>
          <footer class="selection-footer"><button class="selection-submit" :disabled="legacyQueueing||xifan.busy.value||selectionLoading||activeFlow.busy.value||activeFlow.running.value&&!activeFlow.uncertain.value||!activeFlow.selection.value.length||activeFlow.selection.value.length>selectionLimit" @click="submit">{{activeFlow.uncertain.value?'核对入队结果':'加入播放队列'}}</button></footer>
        </template>
      </section>
    </div>
  </div>
</template>
<script setup>
import { computed, inject, onMounted, onBeforeUnmount, ref, toRef, watch } from 'vue';
import { ArrowLeft, LayoutGrid, Rows2 } from 'lucide-vue-next';
import CoverImage from './CoverImage.vue';
import OverflowText from './OverflowText.vue';
import VideoFavoriteImport from './VideoFavoriteImport.vue';
import { usePlayerStore } from '../stores/player';
import { useUserStore } from '../stores/user';
import { useUiStore } from '../stores/ui';
import { useXifanSearch } from '../composables/useXifanSearch';
import { useVideoInput } from '../composables/useVideoInput';
import { useVideoFavoriteImport } from '../composables/useVideoFavoriteImport';
import { searchFeedbackKey,observeEnqueue } from '../composables/useSearchFeedback';
import { musicApi } from '../api/music';
import { videoBvid, isDirectVideoInput } from '../utils/playbackProtocol';
import { roomSession } from '../services/roomSession';

const props=defineProps({platform:String,keyword:String,songs:Array,doSearch:Function});
const player=usePlayerStore(),user=useUserStore(),ui=useUiStore(),feedback=inject(searchFeedbackKey);
const open=ref(true),keyword=toRef(()=>props.keyword),platform=toRef(()=>props.platform);
const xifan=useXifanSearch(player,user,keyword,platform,open,feedback),input=useVideoInput(player,keyword,open);
const parts=useVideoFavoriteImport(player,user,'VIDEO_PARTS',feedback),pgc=useVideoFavoriteImport(player,user,'PGC_EPISODES',feedback);
const kind=ref(''),picked=ref(null),descriptor=ref(null),selectionLoading=ref(false),favorites=ref(null),resultsElement=ref(null);
const legacyQueueing=ref(false);
let request=0;
const enqueueObservers=new Set();onBeforeUnmount(()=>enqueueObservers.forEach(outcome=>outcome.cancel()));
const candidates=computed(()=>props.platform==='xifan'?xifan.items.value.map(item=>({...item,title:item.title,cover:item.coverUrl,summary:item.finished?`全 ${item.currentEpisodes} 集`:`更新至 ${item.currentEpisodes} 集`})):(props.songs||[]).map(item=>({...item,title:String(item.name||item.title||''),cover:item.coverUrl,year:(item.artists||[])[0]||'BILIBILI',summary:''})));
const selectionOpen=computed(()=>!!kind.value||xifan.picker.value);
const activeFlow=computed(()=>kind.value==='xifan'||props.platform==='xifan'?xifan.flow:kind.value==='pgc'?pgc:parts);
const selectionTitle=computed(()=>kind.value==='xifan'?xifan.flow.preview.value?.anime?.title||picked.value?.title||'选择作品':kind.value==='pgc'?pgc.preview.value?.season?.title||picked.value?.title||'选择分集':descriptor.value?.title||picked.value?.title||favorites.value?.title||'选择视频');
const selectionCover=computed(()=>kind.value==='xifan'?xifan.flow.preview.value?.anime?.coverUrl||picked.value?.cover:kind.value==='pgc'?pgc.preview.value?.season?.cover||picked.value?.cover:descriptor.value?.coverUrl||picked.value?.cover);
const rows=computed(()=>kind.value==='parts'?(descriptor.value?.pages||[]).map(row=>({...row,itemId:row.canonicalId,value:row.page,index:`P${row.page}`,selectable:row.withinDurationLimit})):
  activeFlow.value.items.value.map((row,index)=>({...row,value:1,index:kind.value==='xifan'?String(row.episodeNumber||index+1).padStart(2,'0'):row.episodeTitle||String(index+1),title:kind.value==='xifan'?`第 ${row.episodeNumber||index+1} 集 · ${row.title||''}`:row.title||row.episodeTitle||`第 ${index+1} 集`})));
const queued=row=>player.queue.some(item=>item.music.id===row.itemId||row.canonicalId&&item.music.id===row.canonicalId);
const selectable=row=>row.selectable!==false&&!queued(row)&&activeFlow.value.retryable(row.itemId);
const eligible=computed(()=>rows.value.filter(selectable));
const selectionLimit=computed(()=>kind.value==='parts'&&!player.videoFeatures.pageBatchImport?1:activeFlow.value.batchLimit.value);
const allSelected=computed(()=>eligible.value.length>0&&eligible.value.every(row=>activeFlow.value.selected.value[row.itemId]!==undefined));
const someSelected=computed(()=>eligible.value.some(row=>activeFlow.value.selected.value[row.itemId]!==undefined));
function toggle(row,checked){const flow=activeFlow.value;if(checked&&selectionLimit.value===1)flow.selected.value={};const selected={...flow.selected.value};if(checked)selected[row.itemId]=row.value;else delete selected[row.itemId];flow.selected.value=selected;if(flow.selection.value.length>selectionLimit.value)feedback.fail('queue',`本批上限 ${selectionLimit.value} 项，请减少选择`);}
function selectAll(checked){const flow=activeFlow.value;flow.selected.value=checked?Object.fromEntries(eligible.value.map(row=>[row.itemId,row.value])):{};if(flow.selection.value.length>selectionLimit.value)feedback.fail('queue',`本批上限 ${selectionLimit.value} 项，请减少选择`);}
function back(){if(!selectionOpen.value)return false;request++;kind.value='';xifan.closePicker();selectionLoading.value=false;input.invalidate();parts.clearRead();pgc.clearRead();feedback.clear();return true;}
function applyResolved(response){
  if(response.type==='VIDEO'){
    kind.value='parts';descriptor.value=response.video;
    parts.acceptPreview({...response,page:1,items:response.video.pages.map(page=>({itemId:page.canonicalId,page:page.page,selectable:page.withinDurationLimit}))});
    if(response.explicitPage!=null){const row=rows.value.find(row=>row.page===response.explicitPage);if(row&&selectable(row))toggle(row,true);}
  }else if(response.type.startsWith('PGC_')){kind.value='pgc';pgc.acceptPreview({...response,page:1,items:response.episodes});}
  else if(response.type==='FAVORITES'){kind.value='favorites';favorites.value=response.favorites||response;}
}
async function readBilibili(value){
  const id=++request,context=player.playbackContext();if(!context){feedback.fail('load','房间连接尚未就绪');return;}
  selectionLoading.value=true;const ticket=feedback.begin('load');
  try{
    if(player.videoFeatures.inputResolve){const resolved=await input.resolve(value);if(id!==request||!resolved){if(id===request)feedback.finish(ticket,false,input.error.value||'读取失败');return;}if(resolved.response.type==='KEYWORD'){kind.value='';await props.doSearch(resolved.response.query);return;}applyResolved(resolved.response);}
    else{const video=await musicApi.videoMetadata(videoBvid(value));if(id!==request||!player.isContextCurrent(context))return;applyResolved({type:'VIDEO',video,previewId:null});}
    if(id===request)feedback.finish(ticket,true);
  }catch(error){if(id===request)feedback.finish(ticket,false,error.response?.data?.message||'读取视频信息失败');}
  finally{if(id===request)selectionLoading.value=false;}
}
async function choose(item){picked.value=item;if(props.platform==='xifan'){kind.value='xifan';await xifan.preview(item.id);}else{kind.value='parts';await readBilibili(String(item.id).startsWith('bilibili:ep')?item.id:videoBvid(item.id));}}
async function search(){
  back();
  if(props.platform==='xifan'){kind.value='';await xifan.resolve();if(xifan.picker.value)kind.value='xifan';return;}
  if(!props.keyword.trim()){feedback.fail('search','请输入搜索关键词或视频链接');return;}
  if(/next\.xifanacg\.com/.test(props.keyword)){feedback.fail('search','请切换 XIFAN 视频源');return;}
  if(player.videoFeatures.inputResolve){const id=++request,ticket=feedback.begin('search');const resolved=await input.resolve(props.keyword);if(id!==request)return;if(!resolved){feedback.finish(ticket,false,input.error.value||'搜索失败');return;}if(resolved.response.type==='KEYWORD')await props.doSearch(resolved.response.query);else{applyResolved(resolved.response);feedback.finish(ticket,true);}return;}
  if(isDirectVideoInput(props.keyword)){kind.value='parts';await readBilibili(props.keyword);return;}
  await props.doSearch();
}
async function submit(){
  if(legacyQueueing.value)return;
  if(kind.value==='parts'&&!player.videoFeatures.pageBatchImport){
    const row=rows.value.find(row=>parts.selected.value[row.itemId]!==undefined);if(!row)return;
    const ticket=feedback.begin('queue'),context=player.playbackContext();if(!context){feedback.finish(ticket,false,'房间连接尚未就绪');return;}
    legacyQueueing.value=true;
    const outcome=observeEnqueue(player,row.itemId,context),sent=player.enqueue('bilibili-video',row.itemId,context);
    enqueueObservers.add(outcome);
    if(!sent){outcome.cancel();enqueueObservers.delete(outcome);legacyQueueing.value=false;feedback.finish(ticket,false,'无法发送入队请求');return;}
    outcome.check();const success=await outcome.promise;enqueueObservers.delete(outcome);legacyQueueing.value=false;feedback.finish(ticket,success,success?'':'结果暂无法确认，请核对播放队列');return;
  }
  activeFlow.value.submit();
}
watch([platform,keyword,()=>roomSession.generation,()=>player.clientGeneration,()=>player.modeEpoch,()=>player.synchronized],()=>{back();xifan.invalidate();feedback.clear();},{flush:'sync'});
onMounted(()=>{if(props.platform==='xifan')search();});
defineExpose({search,back});
</script>
<style scoped>
.video-search-viewport{flex:1;min-height:0;overflow:clip}.video-search-track{display:grid;grid-template-columns:1fr 1fr;grid-template-rows:minmax(0,1fr);width:200%;height:100%;transition:transform 320ms cubic-bezier(.22,.8,.3,1)}.video-search-track.selected{transform:translateX(-50%)}
.video-candidates{min-width:0;min-height:0;overflow:auto;padding:12px 20px}.candidate-heading,.candidate-actions{display:flex;align-items:center;justify-content:space-between;gap:12px}.candidate-heading{font-size:12px;color:rgb(var(--medical-500));margin-bottom:12px}.candidate-actions button{width:30px;height:30px;display:grid;place-items:center;border-radius:3px}.candidate-actions button:hover{color:rgb(var(--accent));background:rgb(var(--accent)/.08)}.candidate-actions svg{width:15px;height:15px}
.candidate-grid{display:grid;gap:13px;grid-template-columns:repeat(4,minmax(0,1fr))}.candidate-card{display:flex;flex-direction:column;align-items:stretch;text-align:left;min-width:0;overflow:hidden;border:1px solid rgb(var(--medical-200));border-radius:7px;background:rgb(var(--surface));box-shadow:0 2px 5px rgb(var(--overlay)/.04);transition:transform 160ms,border-color 160ms}.candidate-card:hover{transform:translateY(-2px);border-color:rgb(var(--accent))}.candidate-cover{aspect-ratio:3/4;width:100%;flex:none}.candidate-copy{padding:10px 11px;min-width:0;height:66px;display:flex;flex-direction:column;gap:5px}.candidate-title{font-size:14px;font-weight:600}.candidate-meta{display:flex;gap:5px;justify-content:space-between;color:rgb(var(--medical-500));font-size:11px;white-space:nowrap}.candidate-meta span{overflow:hidden;text-overflow:ellipsis}.compact{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.compact .candidate-card{flex-direction:row;height:108px}.compact .candidate-cover{width:78px;height:100%;aspect-ratio:auto}.compact .candidate-copy{flex:1;height:100%;justify-content:center;padding:14px}.compact .candidate-meta{flex-direction:column;gap:2px}.more-candidates{padding:12px;color:rgb(var(--accent));font-size:12px}
.video-selection{display:flex;flex-direction:column;min-width:0;min-height:0;overflow:hidden;padding:0 20px}.selection-heading{display:flex;align-items:center;gap:14px;padding:16px 0;border-bottom:1px solid rgb(var(--medical-200));flex:none}.selection-cover{width:48px;height:64px;flex:none;border-radius:3px}.selection-heading-copy{flex:1;min-width:0}.selection-back{display:flex;align-items:center;gap:6px;text-align:left;width:100%;min-width:0;font-weight:600;margin-bottom:6px}.selection-back svg{width:16px;height:16px;color:rgb(var(--accent));flex:none}.selection-back :deep(.overflow-text){flex:1}.selection-kicker{color:rgb(var(--medical-500));font:11px/1.4 monospace}.source-field{display:flex;align-items:center;gap:12px;padding:14px 0 10px;font-size:12px;color:rgb(var(--medical-500))}.source-field select{flex:1;min-width:0;max-width:100%;padding:7px 10px;border:1px solid rgb(var(--medical-200));border-radius:3px;background:rgb(var(--surface));color:rgb(var(--medical-900))}.selection-tools{display:flex;justify-content:space-between;align-items:center;padding:10px 0;font-size:12px;color:rgb(var(--medical-500))}.selection-tools label{display:flex;align-items:center;gap:7px;min-height:30px}.video-selection input[type=checkbox]{width:15px;height:15px;flex:none;accent-color:rgb(var(--accent))}.selection-episodes{flex:1;min-height:0;overflow:auto;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;align-content:start;padding-bottom:14px}.selection-episode{display:flex;align-items:center;gap:10px;padding:12px;min-height:51px;min-width:0;border:1px solid rgb(var(--medical-200));border-radius:4px;background:rgb(var(--surface))}.selection-episode.checked{border-color:rgb(var(--accent));background:rgb(var(--accent)/.06)}.selection-episode.unavailable{opacity:.5}.episode-code{font:11px/1.5 monospace;color:rgb(var(--medical-500));flex:none}.episode-title{flex:1;font-size:12px}.selection-footer{border-top:1px solid rgb(var(--medical-200));padding:12px 0 16px;flex:none;display:flex;justify-content:flex-end}.selection-submit{background:rgb(var(--accent));color:#fff;min-width:180px;padding:10px 16px;border-radius:3px;font-size:14px}.selection-submit:disabled{opacity:.4}
.candidate-title :deep(.overflow-text-content){max-height:22px}:deep(.candidate-title.reduced-motion){white-space:nowrap;overflow:hidden}:deep(.candidate-title.reduced-motion .overflow-text-content){white-space:nowrap}
.bilibili .candidate-cover{aspect-ratio:16/9}.bilibili .candidate-cover :deep(img){object-fit:contain}.bilibili.compact .candidate-cover{width:44%;max-width:192px;height:auto;aspect-ratio:16/9;align-self:center}
@media(max-width:600px){.candidate-grid.poster{grid-template-columns:repeat(3,minmax(0,1fr))}.selection-episodes{grid-template-columns:1fr}}@media(max-width:430px){.video-candidates{padding:8px 12px}.candidate-grid.poster{grid-template-columns:repeat(2,minmax(0,1fr));gap:11px}.compact{grid-template-columns:1fr}.video-selection{padding-inline:12px}.selection-submit{width:100%}}@media(pointer:coarse){.candidate-actions button{width:44px;height:44px}.selection-tools label,.selection-back,.source-field select{min-height:44px}}@media(prefers-reduced-motion:reduce){.video-search-track,.candidate-card{transition:none}}
</style>
