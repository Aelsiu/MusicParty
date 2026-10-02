<template>
  <div ref="layout" class="room-pairing-card">
    <div class="room-pairing-fields">
      <div ref="codeField" class="room-pairing-field room-pairing-code">
        <span class="room-pairing-label text-[11px] font-bold text-medical-400 font-mono">PAIRING CODE</span>
        <PairingCode compact :code="code" :next-update-at="next" :interval-minutes="intervalMinutes" :clock-offset="offset" />
      </div>
      <div ref="timeField" class="room-pairing-field room-pairing-time" :style="{ transform: `translateX(${uptimeShift}px)` }">
        <span class="room-pairing-label text-[11px] font-bold text-medical-400 font-mono">UPTIME</span>
        <span class="room-pairing-value font-mono text-sm tabular-nums whitespace-nowrap">{{ countdown }}</span>
      </div>
      <div ref="showField" class="room-pairing-field room-pairing-show">
        <span class="room-pairing-label text-[11px] font-bold text-medical-400 font-mono">SHOW</span>
        <div class="room-pairing-value">
          <RoundSwitch :model-value="pairingOpen" :disabled="!loaded || saving" label="SHOW：在房间内展示配对码" @update:model-value="setShow" />
        </div>
      </div>
      <div class="room-pairing-field room-pairing-access">
        <span class="room-pairing-label text-[11px] font-bold text-medical-400 font-mono">OPEN ROOM</span>
        <div class="room-pairing-value">
          <div class="room-access-buttons" :class="{ 'is-public': publicRoom }" role="group" aria-label="房间公开状态" :aria-busy="saving">
            <button type="button" class="room-access-option font-mono" :aria-pressed="!publicRoom" :disabled="!loaded || saving" aria-label="PRIVATE：私密房间" @click="setPublic(false)">
              <span class="room-access-content" aria-hidden="true"><span class="room-access-text">PRIVATE</span><Lock class="room-access-icon" /></span>
            </button>
            <button type="button" class="room-access-option font-mono" :aria-pressed="publicRoom" :disabled="!loaded || saving" aria-label="PUBLIC：允许通过房间地址直接进入" @click="setPublic(true)">
              <span class="room-access-content" aria-hidden="true"><span class="room-access-text">PUBLIC</span><LockOpen class="room-access-icon" /></span>
            </button>
          </div>
        </div>
      </div>
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useEventListener, useResizeObserver } from '@vueuse/core';
import { Lock, LockOpen } from 'lucide-vue-next';
import PairingCode from './PairingCode.vue';
import RoundSwitch from './RoundSwitch.vue';
import { roomsApi } from '../api/rooms';
import { roomSession } from '../services/roomSession';
import { pairingRemaining, pairingCountdown } from '../utils/pairingClock';
const code=ref(''),next=ref(0),intervalMinutes=ref(10),now=ref(Date.now()),offset=ref(0),error=ref('');
const publicRoom=ref(false),pairingOpen=ref(false),loaded=ref(false),saving=ref(false);
const layout=ref(null),codeField=ref(null),timeField=ref(null),showField=ref(null),uptimeShift=ref(0);
let timer,refreshing=false,alive=true,generation=0;
const remaining=computed(()=>pairingRemaining(next.value,now.value,offset.value));
const countdown=computed(()=>pairingCountdown(remaining.value));
const isCurrent=(id,token,current)=>alive&&id===roomSession.roomId&&token===roomSession.managerToken&&current===generation;
function apply(value){
  code.value=value.pairingCode;next.value=value.nextUpdateAt;intervalMinutes.value=value.pairingIntervalMinutes;
  offset.value=(value.serverTime||Date.now())-Date.now();now.value=Date.now();
  publicRoom.value=value.publicRoom===true;pairingOpen.value=value.pairingOpen===true;loaded.value=true;error.value='';
}
async function refresh(){
  if(refreshing||saving.value||!alive||!roomSession.roomId)return;
  refreshing=true;const id=roomSession.roomId,token=roomSession.managerToken,current=++generation;
  try{const value=await roomsApi.manage(id);if(isCurrent(id,token,current))apply(value);}
  catch(e){if(isCurrent(id,token,current)){code.value='';loaded.value=false;error.value=e.response?.data?.message||'配对码获取失败';}}
  finally{refreshing=false;if(alive&&(roomSession.roomId!==id||roomSession.managerToken!==token))refresh();}
}
async function updateSetting(update,message){
  if(!loaded.value||saving.value||!alive)return;
  const id=roomSession.roomId,token=roomSession.managerToken,current=++generation;saving.value=true;
  try{const value=await update(id);if(isCurrent(id,token,current))apply(value);}
  catch(e){if(isCurrent(id,token,current))error.value=e.response?.data?.message||message;}
  finally{saving.value=false;if(alive&&!loaded.value)refresh();}
}
const setPublic=open=>{
  if(open===publicRoom.value)return;
  return updateSetting(id=>roomsApi.setPublic(id,open),'OPEN ROOM 状态更新失败');
};
const setShow=show=>updateSetting(id=>roomsApi.setPairingOpen(id,show),'SHOW 状态更新失败');
function alignFields(){
  if(!codeField.value||!timeField.value||!showField.value)return;
  const codeBounds=codeField.value.getBoundingClientRect(),timeBounds=timeField.value.getBoundingClientRect(),showBounds=showField.value.getBoundingClientRect();
  if(Math.abs(codeBounds.top-showBounds.top)>1){uptimeShift.value=0;return;}
  const codeCenter=codeBounds.left+codeBounds.width/2,showCenter=showBounds.left+showBounds.width/2;
  const naturalTimeCenter=timeBounds.left+timeBounds.width/2-uptimeShift.value;
  uptimeShift.value=(codeCenter+showCenter)/2-naturalTimeCenter;
}
useResizeObserver([layout,codeField],alignFields);
watch(()=>[roomSession.roomId,roomSession.managerToken],()=>{++generation;code.value='';next.value=0;publicRoom.value=false;pairingOpen.value=false;loaded.value=false;refresh();},{immediate:true});
useEventListener(window,'musicparty:pairing',refresh);
useEventListener(document,'visibilitychange',()=>{if(!document.hidden)refresh();});
onMounted(()=>{timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);});
onBeforeUnmount(()=>{alive=false;++generation;clearInterval(timer);});
</script>
<style scoped>
.room-pairing-card{container:room-pairing / inline-size}
/* Keep OPEN ROOM anchored while tightening gaps to fit the dashboard's side column. */
.room-pairing-fields{display:grid;grid-template-columns:minmax(80px,1fr) 54px 32px 144px;column-gap:clamp(8px,calc((100cqw - 310px) / 3),22px);align-items:start}
.room-pairing-field{display:grid;grid-template-rows:16px auto;row-gap:14px;justify-items:center;min-width:0;text-align:center}
.room-pairing-label{line-height:16px;white-space:nowrap}
.room-pairing-value{display:flex;align-items:center;justify-content:center;min-height:28px}
.room-pairing-code{width:min-content;justify-self:start}
.room-pairing-code :deep(.pairing-code){width:min-content}
.room-access-buttons{display:grid;grid-template-columns:60px 28px;gap:0;width:88px;height:28px;flex-shrink:0;transition:grid-template-columns 340ms cubic-bezier(.22,1,.36,1)}
.room-access-buttons.is-public{grid-template-columns:28px 60px}
.room-access-option{position:relative;box-sizing:border-box;min-width:0;height:28px;padding:0;border:1px solid rgb(var(--medical-200));border-radius:0;background:rgb(var(--medical-50));color:rgb(var(--medical-600));font-size:11px;font-weight:400;cursor:pointer;transition:color 150ms ease,background-color 200ms ease,border-color 200ms ease,box-shadow 200ms ease}
.room-access-option+.room-access-option{border-left:0}
.room-access-option[aria-pressed='true']{border-color:rgb(var(--accent));background:rgb(var(--accent) / .15);color:rgb(var(--accent));font-weight:700}
.room-access-buttons.is-public .room-access-option:first-child{border-right-color:rgb(var(--accent))}
.room-access-option:hover:not(:disabled){color:rgb(var(--accent))}
.room-access-option[aria-pressed='true']:hover:not(:disabled){box-shadow:0 3px 10px rgb(var(--accent) / .24)}
.room-access-option:disabled{opacity:.4;cursor:not-allowed}
.room-access-option:focus-visible{z-index:2;outline:2px solid rgb(var(--accent));outline-offset:2px}
.room-access-content{position:relative;display:block;width:100%;height:100%;overflow:hidden;pointer-events:none}
.room-access-text,.room-access-icon{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);transition:opacity 140ms ease}
.room-access-text{white-space:nowrap;opacity:0}
.room-access-icon{width:16px;height:16px;opacity:1}
.room-access-option[aria-pressed='true'] .room-access-text{opacity:1;transition-delay:80ms}
.room-access-option[aria-pressed='true'] .room-access-icon{opacity:0}
@container room-pairing (width < 334px){
  .room-pairing-fields{grid-template-columns:minmax(80px,1.4fr) minmax(88px,1fr);gap:20px 16px}
  .room-pairing-show{justify-items:start;text-align:left}
}
@media(pointer:coarse){.room-access-option::after{content:'';position:absolute;left:0;top:50%;width:100%;height:44px;transform:translateY(-50%)}}
@media(prefers-reduced-motion:reduce){.room-access-buttons,.room-access-option,.room-access-text,.room-access-icon{transition:none}}
</style>
