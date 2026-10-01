<template>
  <div>
    <div class="grid grid-cols-[minmax(0,1fr)_auto_auto] gap-x-3 gap-y-2 items-center">
      <span class="text-[11px] font-bold text-medical-400 font-mono">PAIRING CODE</span>
      <span class="text-[11px] font-bold text-medical-400 font-mono">UPTIME</span>
      <span class="text-[11px] font-bold text-medical-400 font-mono">OPEN</span>
      <PairingCode :code="code" :next-update-at="next" :interval-minutes="intervalMinutes" :clock-offset="offset" />
      <span class="font-mono text-sm tabular-nums whitespace-nowrap">{{ countdown }}</span>
      <RoundSwitch :model-value="pairingOpen" :disabled="!loaded || saving" label="在房间内展示配对码" @update:model-value="setOpen" />
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useEventListener } from '@vueuse/core';
import PairingCode from './PairingCode.vue';
import RoundSwitch from './RoundSwitch.vue';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';
import { pairingRemaining, pairingCountdown } from '../utils/pairingClock';
const code=ref(''),next=ref(0),intervalMinutes=ref(10),now=ref(Date.now()),offset=ref(0),error=ref('');
const pairingOpen=ref(false),loaded=ref(false),saving=ref(false);
let timer,refreshing=false,alive=true,generation=0;
const remaining=computed(()=>pairingRemaining(next.value,now.value,offset.value));
const countdown=computed(()=>pairingCountdown(remaining.value));
const isCurrent=(id,token,current)=>alive&&id===roomSession.roomId&&token===roomSession.managerToken&&current===generation;
function apply(value){
  code.value=value.pairingCode;next.value=value.nextUpdateAt;intervalMinutes.value=value.pairingIntervalMinutes;
  offset.value=(value.serverTime||Date.now())-Date.now();now.value=Date.now();
  pairingOpen.value=value.pairingOpen===true;loaded.value=true;error.value='';
}
async function refresh(){
  if(refreshing||saving.value||!alive||!roomSession.roomId)return;
  refreshing=true;const id=roomSession.roomId,token=roomSession.managerToken,current=++generation;
  try{const value=await roomsApi.manage(id);if(isCurrent(id,token,current))apply(value);}
  catch(e){if(isCurrent(id,token,current)){code.value='';loaded.value=false;error.value=e.response?.data?.message||'配对码获取失败';}}
  finally{refreshing=false;}
}
async function setOpen(open){
  if(!loaded.value||saving.value||!alive)return;
  const id=roomSession.roomId,token=roomSession.managerToken,current=++generation;saving.value=true;
  try{const value=await roomsApi.setPairingOpen(id,open);if(isCurrent(id,token,current))apply(value);}
  catch(e){if(isCurrent(id,token,current))error.value=e.response?.data?.message||'OPEN 状态更新失败';}
  finally{saving.value=false;if(alive)refresh();}
}
watch(()=>[roomSession.roomId,roomSession.managerToken],()=>{++generation;code.value='';next.value=0;pairingOpen.value=false;loaded.value=false;refresh();},{immediate:true});
useEventListener(window,'musicparty:pairing',refresh);
useEventListener(document,'visibilitychange',()=>{if(!document.hidden)refresh();});
onMounted(()=>{timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);});
onBeforeUnmount(()=>{alive=false;++generation;clearInterval(timer);});
</script>
