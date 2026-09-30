<template>
  <div>
    <div class="flex items-center justify-between gap-2">
      <label class="text-[11px] font-bold text-medical-400 uppercase font-mono whitespace-nowrap">PAIRING CODE</label>
      <div class="flex items-center gap-1.5 shrink-0"><div class="pairing-ring" :style="{ '--progress': `${remaining/600*100}%` }" aria-hidden="true"></div><div><span class="font-mono text-xs">{{ countdown }}</span><span class="block text-[10px] text-medical-400">下一次整十分钟更新</span></div></div>
    </div>
    <div class="flex items-center gap-3 mt-2">
      <span class="font-mono text-3xl tracking-[.25em] text-accent">{{ code || '----' }}</span>
      <button @click="copy" :disabled="!code" class="relative p-2 text-medical-400 disabled:opacity-40" aria-label="复制配对码">
        <Copy :key="copySequence" class="w-4 h-4" :class="{ 'copy-pulse': copied }" />
        <span v-if="copied" :key="copySequence" role="status" class="copy-feedback absolute bottom-full left-1/2 whitespace-nowrap text-[11px] text-accent pointer-events-none">已复制</span>
      </button>
    </div>
    <p class="text-[11px] text-medical-400 mt-3">更新不影响已连接的成员</p>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { Copy } from 'lucide-vue-next';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';
const code=ref(''),next=ref(0),now=ref(Date.now()),offset=ref(0),error=ref('');let timer,refreshing=false,alive=true;
const copied=ref(false),copySequence=ref(0);let copyTimer,copyGeneration=0;
const remaining=computed(()=>Math.max(0,Math.ceil((next.value-now.value-offset.value)/1000)));
const countdown=computed(()=>`${Math.floor(remaining.value/60).toString().padStart(2,'0')}:${(remaining.value%60).toString().padStart(2,'0')}`);
async function refresh(){if(refreshing||!alive)return;refreshing=true;try{const value=await roomsApi.manage(roomSession.roomId);if(alive){code.value=value.pairingCode;next.value=value.nextUpdateAt;offset.value=(value.serverTime||Date.now())-Date.now();error.value='';}}catch(e){if(alive)error.value=e.response?.data?.message||'配对码获取失败';}finally{refreshing=false;}}
async function copy(){
  if(!code.value)return;
  const current=++copyGeneration;
  try{
    await navigator.clipboard.writeText(code.value);
    if(!alive||current!==copyGeneration)return;
    error.value='';clearTimeout(copyTimer);copied.value=true;copySequence.value++;
    copyTimer=setTimeout(()=>{copied.value=false;},500);
  }catch{if(alive&&current===copyGeneration){clearTimeout(copyTimer);copied.value=false;error.value='无法访问剪贴板，请手动复制配对码';}}
}
onMounted(()=>{refresh();timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);window.addEventListener('musicparty:pairing',refresh);});
onBeforeUnmount(()=>{alive=false;clearInterval(timer);clearTimeout(copyTimer);window.removeEventListener('musicparty:pairing',refresh);});
</script>
<style scoped>
.pairing-ring{width:24px;height:24px;border-radius:50%;background:conic-gradient(rgb(var(--accent)) var(--progress),rgb(var(--medical-200)) 0);position:relative}.pairing-ring:after{content:'';position:absolute;inset:3px;background:rgb(var(--surface));border-radius:50%}
.copy-pulse{animation:copy-pulse .5s ease-in-out}
.copy-feedback{animation:copy-float .5s ease-out both}
@keyframes copy-pulse{0%,100%{color:inherit}30%,55%{color:rgb(var(--accent))}}
@keyframes copy-float{0%{opacity:0;transform:translate(-50%,0)}15%{opacity:1}100%{opacity:0;transform:translate(-50%,-18px)}}
@media(prefers-reduced-motion:reduce){@keyframes copy-float{0%,100%{opacity:0;transform:translateX(-50%)}15%,75%{opacity:1;transform:translateX(-50%)}}}
</style>
