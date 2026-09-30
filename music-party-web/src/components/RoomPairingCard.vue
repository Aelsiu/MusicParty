<template>
  <div>
    <div class="grid grid-cols-[minmax(0,1fr)_auto] gap-x-2 gap-y-2 items-center">
      <span class="text-[11px] font-bold text-medical-400 font-mono">PAIRING CODE</span>
      <span class="text-[11px] font-bold text-medical-400 font-mono">UPTIME</span>
      <PairingCode :code="code" />
      <div class="flex items-center gap-2 shrink-0">
        <div class="pairing-ring" :style="{ '--progress': `${remaining/600*100}%` }" aria-hidden="true"></div>
        <span class="font-mono text-sm">{{ countdown }}</span>
      </div>
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import PairingCode from './PairingCode.vue';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';
const code=ref(''),next=ref(0),now=ref(Date.now()),offset=ref(0),error=ref('');let timer,refreshing=false,alive=true;
const remaining=computed(()=>Math.max(0,Math.ceil((next.value-now.value-offset.value)/1000)));
const countdown=computed(()=>`${Math.floor(remaining.value/60).toString().padStart(2,'0')}:${(remaining.value%60).toString().padStart(2,'0')}`);
async function refresh(){if(refreshing||!alive)return;refreshing=true;try{const value=await roomsApi.manage(roomSession.roomId);if(alive){code.value=value.pairingCode;next.value=value.nextUpdateAt;offset.value=(value.serverTime||Date.now())-Date.now();error.value='';}}catch(e){if(alive)error.value=e.response?.data?.message||'配对码获取失败';}finally{refreshing=false;}}
onMounted(()=>{refresh();timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);window.addEventListener('musicparty:pairing',refresh);});
onBeforeUnmount(()=>{alive=false;clearInterval(timer);window.removeEventListener('musicparty:pairing',refresh);});
</script>
<style scoped>
.pairing-ring{width:24px;height:24px;border-radius:50%;background:conic-gradient(rgb(var(--accent)) var(--progress),rgb(var(--medical-200)) 0);position:relative}.pairing-ring:after{content:'';position:absolute;inset:3px;background:rgb(var(--surface));border-radius:50%}
</style>
