<template>
  <div class="room-pairing-card">
    <div class="room-pairing-fields">
      <div class="room-pairing-data" role="group" aria-label="配对码和有效期">
        <div class="room-pairing-field room-pairing-code">
          <PairingCode compact :code="code" :next-update-at="next" :interval-minutes="intervalMinutes" :clock-offset="offset" />
        </div>
        <div class="room-pairing-field room-pairing-time">
          <span class="room-pairing-value font-mono text-sm tabular-nums whitespace-nowrap">{{ countdown }}</span>
        </div>
      </div>
      <span class="room-pairing-divider" aria-hidden="true"></span>
      <div class="room-pairing-settings" role="group" aria-label="分享和房间公开状态">
        <div class="room-pairing-field room-pairing-share">
          <button type="button" class="room-share-option font-mono" :aria-pressed="roomSession.shareEnabled"
                  :disabled="!loaded || saving" :aria-busy="saving"
                  :aria-label="roomSession.shareEnabled ? '允许全员分享房间，点击关闭' : '全员分享已关闭，点击允许'"
                  @click="setShare(!roomSession.shareEnabled)">{{ roomSession.shareEnabled ? 'OK SHARE' : 'NO SHARE' }}</button>
        </div>
        <div class="room-pairing-field room-pairing-access">
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
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { useEventListener } from '@vueuse/core';
import { Lock, LockOpen } from 'lucide-vue-next';
import PairingCode from './PairingCode.vue';
import { roomsApi } from '../api/rooms';
import { roomSession, syncRoomShare } from '../services/roomSession';
import { pairingRemaining, pairingCountdown } from '../utils/pairingClock';
const code=ref(''),next=ref(0),intervalMinutes=ref(10),now=ref(Date.now()),offset=ref(0),error=ref('');
const publicRoom=ref(false),loaded=ref(false),saving=ref(false);
let timer,refreshing=false,alive=true,generation=0;
const remaining=computed(()=>pairingRemaining(next.value,now.value,offset.value));
const countdown=computed(()=>pairingCountdown(remaining.value));
const isCurrent=(id,token,current)=>alive&&id===roomSession.roomId&&token===roomSession.managerToken&&current===generation;
function apply(value,revision){
  code.value=value.pairingCode;next.value=value.nextUpdateAt;intervalMinutes.value=value.pairingIntervalMinutes;
  offset.value=(value.serverTime||Date.now())-Date.now();now.value=Date.now();
  publicRoom.value=value.publicRoom===true;
  if(revision===roomSession.shareRevision)syncRoomShare(value.shareEnabled,roomSession.roomId,roomSession.generation);
  loaded.value=true;error.value='';
}
async function refresh(){
  if(refreshing||saving.value||!alive||!roomSession.roomId)return;
  refreshing=true;const id=roomSession.roomId,token=roomSession.managerToken,current=++generation,revision=roomSession.shareRevision;
  try{const value=await roomsApi.manage(id);if(isCurrent(id,token,current))apply(value,revision);}
  catch(e){if(isCurrent(id,token,current)){code.value='';loaded.value=false;error.value=e.response?.data?.message||'配对码获取失败';}}
  finally{refreshing=false;if(alive&&(roomSession.roomId!==id||roomSession.managerToken!==token))refresh();}
}
async function updateSetting(update,message){
  if(!loaded.value||saving.value||!alive)return;
  const id=roomSession.roomId,token=roomSession.managerToken,current=++generation,revision=roomSession.shareRevision;saving.value=true;
  try{const value=await update(id);if(isCurrent(id,token,current))apply(value,revision);}
  catch(e){if(isCurrent(id,token,current))error.value=e.response?.data?.message||message;}
  finally{saving.value=false;if(alive&&!loaded.value)refresh();}
}
const setPublic=open=>{
  if(open===publicRoom.value)return;
  return updateSetting(id=>roomsApi.setPublic(id,open),'房间公开状态更新失败');
};
const setShare=enabled=>updateSetting(id=>roomsApi.setShareEnabled(id,enabled),'分享状态更新失败');
watch(()=>[roomSession.roomId,roomSession.managerToken],()=>{++generation;code.value='';next.value=0;publicRoom.value=false;loaded.value=false;refresh();},{immediate:true});
useEventListener(window,'musicparty:pairing',refresh);
useEventListener(document,'visibilitychange',()=>{if(!document.hidden)refresh();});
onMounted(()=>{timer=setInterval(()=>{now.value=Date.now();if(remaining.value===0)refresh();},1000);});
onBeforeUnmount(()=>{alive=false;++generation;clearInterval(timer);});
</script>
<style scoped>
.room-pairing-card{container:room-pairing / inline-size}
.room-pairing-fields{display:grid;grid-template-columns:max-content 1px minmax(0,1fr);gap:12px;align-items:center}
.room-pairing-data,.room-pairing-settings{display:flex;align-items:center;gap:12px;min-width:0}
.room-pairing-settings{justify-content:flex-end}
.room-pairing-divider{width:1px;height:28px;background:rgb(var(--medical-200))}
.room-pairing-field{display:flex;align-items:center;justify-content:center;min-width:0;text-align:center}
.room-pairing-value{display:flex;align-items:center;justify-content:center;min-height:28px}
.room-pairing-code{width:min-content;justify-self:start}
.room-pairing-code :deep(.pairing-code){width:min-content}
.room-share-option{position:relative;width:76px;height:28px;padding:0;border:1px solid rgb(var(--medical-200));border-radius:0;background:rgb(var(--medical-50));color:rgb(var(--medical-600));font-size:11px;font-weight:400;white-space:nowrap;transition:color 200ms ease,background-color 340ms ease,border-color 340ms ease,box-shadow 200ms ease}
.room-share-option[aria-pressed='true']{border-color:rgb(var(--accent));background:rgb(var(--accent) / .15);color:rgb(var(--accent));font-weight:700}
.room-share-option[aria-pressed='true']:hover:not(:disabled){box-shadow:0 3px 10px rgb(var(--accent) / .24)}
.room-share-option:disabled{opacity:.4;cursor:not-allowed}
.room-share-option:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:2px}
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
@container room-pairing (width < 350px){
  .room-pairing-fields{grid-template-columns:minmax(0,1fr)}
  .room-pairing-divider{width:100%;height:1px}
  .room-pairing-settings{justify-content:flex-start}
}
@media(pointer:coarse){
  .room-pairing-data,.room-pairing-settings{min-height:44px}
  .room-access-buttons{width:108px;grid-template-columns:64px 44px}.room-access-buttons.is-public{grid-template-columns:44px 64px}
  .room-share-option::after,.room-access-option::after{content:'';position:absolute;left:0;top:50%;width:100%;height:44px;transform:translateY(-50%)}
  @container room-pairing (width < 370px){
    .room-pairing-fields{grid-template-columns:minmax(0,1fr)}
    .room-pairing-divider{width:100%;height:1px}
    .room-pairing-settings{justify-content:flex-start}
  }
}
@media(prefers-reduced-motion:reduce){.room-share-option,.room-access-buttons,.room-access-option,.room-access-text,.room-access-icon{transition:none}}
</style>
