<template>
  <div>
    <label class="block text-[11px] font-bold text-medical-400 uppercase font-mono">PAIRING CODE</label>
    <div class="flex items-center gap-3 mt-2"><span class="font-mono text-3xl tracking-[.25em] text-accent">{{ code || '----' }}</span><button @click="copy" class="p-2 text-medical-400" aria-label="复制配对码"><Copy class="w-4 h-4" /></button></div>
    <div class="flex items-center gap-3 mt-3"><div class="pairing-ring" :style="{ '--progress': `${remaining/600*100}%` }" aria-hidden="true"></div><div><span class="font-mono text-sm">{{ countdown }}</span><span class="block text-[11px] text-medical-400">下一次整十分钟更新</span></div></div>
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
const remaining=computed(()=>Math.max(0,Math.ceil((next.value-now.value-offset.value)/1000)));
const countdown=computed(()=>`${Math.floor(remaining.value/60).toString().padStart(2,'0')}:${(remaining.value%60).toString().padStart(2,'0')}`);
async function refresh(){if(refreshing||!alive)return;refreshing=true;try{const value=await roomsApi.manage(roomSession.roomId);if(alive){code.value=value.pairingCode;next.value=value.nextUpdateAt;offset.value=(value.serverTime||Date.now())-Date.now();error.value='';}}catch(e){if(alive)error.value=e.response?.data?.message||'配对码获取失败';}finally{refreshing=false;}}
async function copy(){try{await navigator.clipboard.writeText(code.value);}catch{error.value='无法访问剪贴板，请手动复制配对码';}}
onMounted(()=>{refresh();timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);window.addEventListener('musicparty:pairing',refresh);});
onBeforeUnmount(()=>{alive=false;clearInterval(timer);window.removeEventListener('musicparty:pairing',refresh);});
</script>
<style scoped>
.pairing-ring{width:32px;height:32px;border-radius:50%;background:conic-gradient(rgb(var(--accent)) var(--progress),rgb(var(--medical-200)) 0);position:relative}.pairing-ring:after{content:'';position:absolute;inset:4px;background:rgb(var(--surface));border-radius:50%}
</style>
