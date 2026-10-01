<template>
  <div v-if="open" class="fixed inset-0 z-[115] bg-overlay/60 backdrop-blur-sm flex items-start justify-center p-4 sm:p-8 overflow-y-auto">
    <section class="w-full max-w-2xl bg-surface border border-medical-200 shadow-2xl chamfer-br my-auto" :class="{ 'room-manager-panel': view==='rooms' }">
      <header class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center gap-3">
        <h2 class="font-mono font-bold tracking-widest text-sm flex items-center gap-2"><ShieldCheck class="w-4 h-4" /> {{ view==='create'?'CREATE ROOM':view.startsWith('license-')?'LICENSE MANAGER':'ROOM MANAGER' }}</h2>
        <button @click="close" aria-label="关闭房间管理" class="p-2"><X class="w-4 h-4" /></button>
      </header>
      <div class="p-5 sm:p-7" :class="{ 'room-manager-body': view==='rooms' }">
        <template v-if="view==='rooms'">
          <div class="flex justify-between text-xs text-medical-500 gap-3 mb-5"><span>{{ roomSession.root?'最高许可':'所属房间' }} / {{ roomSession.licenseId }}</span><span class="font-mono shrink-0">{{ ownCount }} / 9</span></div>
          <div v-if="roomSession.root" class="flex gap-2 flex-wrap mb-5">
            <button v-for="item in tabs" :key="item.id" @click="switchTab(item.id)" :disabled="busy" :class="tab===item.id?'bg-strong text-white':'text-medical-500'" class="border border-medical-200 px-3 py-2 text-xs">{{ item.label }}</button>
          </div>
          <template v-if="tab!=='licenses'">
            <div class="room-manager-list border-t border-medical-200">
              <div v-for="room in visibleRooms" :key="room.id" class="flex items-center gap-2 sm:gap-3 py-4 border-b border-medical-200">
                <button @click="chooseRoom(room)" :disabled="busy" class="text-left flex-1 min-w-0"><span class="block text-base font-bold break-words">{{ room.name }}</span><span class="block text-[11px] text-medical-400 font-mono mt-1">ID {{ room.id }}<template v-if="roomSession.root && tab==='all'"> / {{ room.ownerId }}</template></span></button>
                <PairingCode :code="room.pairingCode" compact :copy-label="`复制 ${room.name} 的配对码`" />
                <button @click="deleteRoom(room.id)" :disabled="busy" :aria-label="`删除房间 ${room.name}，三秒内连续点击三次`" class="room-manager-action">
                  <Trash2 :key="actionEffect.key===`room-delete:${room.id}`?actionEffect.sequence:0" class="w-4 h-4" :class="{ 'action-pulse': actionEffect.key===`room-delete:${room.id}` }" />
                  <span v-if="actionEffect.key===`room-delete:${room.id}`" :key="actionEffect.sequence" role="status" class="action-feedback absolute bottom-full left-1/2 whitespace-nowrap text-[11px] text-accent pointer-events-none">{{ actionEffect.count }}</span>
                </button>
              </div>
            </div>
            <p v-if="!visibleRooms.length && !loading" class="info-tip py-7 text-center text-xs text-medical-400">暂无房间，创建后即可邀请成员加入</p>
            <button @click="view='create';error='';requestId=newRequestId()" :disabled="busy||ownCount>=9" class="mt-6 w-full bg-accent text-white font-bold py-3 flex items-center justify-center gap-2 disabled:opacity-40"><Plus class="w-4 h-4" /> CREATE ROOM</button>
            <button @click="refresh" :disabled="loading" class="block mx-auto text-[11px] text-accent mt-1 p-2">{{ loading?'LOADING...':'REFRESH' }}</button>
          </template>
          <template v-else>
            <div class="flex justify-between text-xs text-medical-400 mb-4"><span>普通许可清单</span><span class="font-mono">{{ licenses.length }} LICENSES</span></div>
            <div class="room-manager-list border-t border-medical-200"><div v-for="license in licenses" :key="license.id" class="flex items-center gap-2 py-4 border-b border-medical-200">
              <div class="flex-1 min-w-0">
                <div class="flex items-center gap-4 min-w-0">
                  <span class="font-bold shrink-0">{{ license.id }}</span>
                  <button @click="editLicense('license-note',license)" :disabled="actionPending" :aria-label="`编辑 ${license.id} 的备注`" :title="license.note || '添加备注'" class="inline-flex min-w-0 items-center gap-2 text-xs text-medical-500 hover:text-accent"><span class="truncate">{{ license.note || '添加备注' }}</span><Pencil class="w-3 h-3 shrink-0" /></button>
                </div>
                <div class="flex items-center gap-2 mt-2"><span class="font-mono text-xs break-all" :data-license-key="license.id">{{ revealedKeys.has(license.id)?license.key:'••••••••' }}</span><button @click="toggleKey(license.id)" :aria-label="`${revealedKeys.has(license.id)?'隐藏':'显示'} ${license.id} 的密钥`" class="p-1 text-medical-400 hover:text-accent"><EyeOff v-if="revealedKeys.has(license.id)" class="w-4 h-4"/><Eye v-else class="w-4 h-4"/></button></div>
                <span class="block text-[11px] text-medical-400 mt-2">{{ license.roomCount }} 个所属房间</span>
              </div>
              <div class="flex items-center gap-1 shrink-0">
                <button @click="licenseAction('license-update',license)" :disabled="actionPending" :aria-label="`更新 ${license.id} 的密钥`" class="room-manager-action"><ArrowUpToLine :key="actionEffect.key===`license-update:${license.id}`?actionEffect.sequence:0" class="w-4 h-4" :class="{ 'action-pulse': actionEffect.key===`license-update:${license.id}` }" /></button>
                <button @click="licenseAction('license-delete',license)" :disabled="actionPending" :aria-label="`删除许可 ${license.id}`" class="room-manager-action"><Trash2 :key="actionEffect.key===`license-delete:${license.id}`?actionEffect.sequence:0" class="w-4 h-4" :class="{ 'action-pulse': actionEffect.key===`license-delete:${license.id}` }" /></button>
              </div>
            </div></div>
            <button @click="editLicense('license-add')" :disabled="actionPending" class="w-full bg-accent text-white font-bold py-3 mt-6 flex items-center justify-center gap-2"><Plus class="w-4 h-4" /> ADD LICENSE</button>
            <button @click="refresh" :disabled="loading" class="block mx-auto text-[11px] text-accent mt-1 p-2">{{ loading?'LOADING...':'REFRESH' }}</button>
          </template>
        </template>
        <form v-else-if="view==='create'" @submit.prevent="submitRoom">
          <label for="new-room-name" class="text-xs font-bold text-medical-500 block mb-2">房间名</label>
          <input id="new-room-name" v-model="roomName" type="text" inputmode="text" autocomplete="off" :spellcheck="false" @compositionstart="composing=true" @compositionend="composing=false" @keydown.enter="guardComposition" class="w-full bg-medical-50 border border-medical-200 px-3 py-3 text-base" placeholder="名称可重复">
          <p class="info-tip text-[11px] text-medical-400 mt-2">{{ graphemes(roomName).length }} / 16 · 中文与 emoji 均可</p>
          <div class="flex gap-2 mt-6"><button type="button" @click="cancelEdit" class="flex-1 border border-medical-200 py-3 text-xs font-bold">CANCEL</button><button :disabled="busy||composing" class="flex-1 bg-accent text-white py-3 text-xs font-bold disabled:opacity-40">{{ busy?'CREATING...':'CREATE ROOM' }}</button></div>
        </form>
        <form v-else @submit.prevent="submitLicense">
          <template v-if="view==='license-delete'"><p class="font-bold break-words">{{ selectedLicense.id }}</p><p class="info-tip text-xs text-medical-500 mt-3">删除该许可将同时删除其 {{ selectedLicense.roomCount }} 个房间，关联连接、直播和登录任务将一并结束</p></template>
          <template v-else-if="view==='license-note'">
            <p class="font-bold mb-4">{{ selectedLicense.id }}</p>
            <label for="license-note" class="text-xs font-bold text-medical-500 block mb-2">许可备注</label>
            <input id="license-note" v-model="licenseNote" autocomplete="off" @compositionstart="composing=true" @compositionend="composing=false" @keydown.enter="guardComposition" class="w-full bg-medical-50 border border-medical-200 px-3 py-3 text-base" placeholder="最多 16 个可见字符">
            <p class="info-tip text-[11px] text-medical-400 mt-2">{{ graphemes(licenseNote).length }} / 16 · 可留空，支持中文与 emoji</p>
          </template>
          <template v-else>
            <label for="license-key" class="text-xs font-bold text-medical-500 block mb-2">{{ view==='license-update'?'新的许可密钥':'许可密钥' }}</label>
            <div class="flex gap-2"><input id="license-key" v-model="licenseKey" :type="showKey?'text':'password'" autocomplete="off" class="min-w-0 flex-1 bg-medical-50 border border-medical-200 px-3 py-3 text-base"><button type="button" @click="showKey=!showKey" class="border border-medical-200 px-3 text-xs" :aria-label="showKey?'隐藏密钥':'显示密钥'"><Eye v-if="!showKey" class="w-4 h-4"/><EyeOff v-else class="w-4 h-4"/></button></div>
            <button type="button" @click="licenseKey=randomLicenseKey();showKey=true" class="text-accent text-xs mt-3 flex items-center gap-2"><Shuffle class="w-4 h-4"/> RANDOM</button>
            <p class="info-tip text-[11px] text-medical-400 mt-3">8–16 位大小写英文字母、数字或符号，不含空白</p>
            <p v-if="view==='license-update'" class="info-tip text-[11px] text-medical-400 mt-3">所属房间和备注保留，旧密钥和旧管理会话立即失效</p>
          </template>
          <div class="flex gap-2 mt-6"><button type="button" @click="cancelEdit" class="flex-1 border border-medical-200 py-3 text-xs font-bold">CANCEL</button><button :disabled="busy||composing" :class="view==='license-delete'?'border border-medical-200 text-accent':'bg-accent text-white'" class="flex-1 py-3 text-xs font-bold disabled:opacity-40">{{ busy?'SAVING...':view==='license-delete'?'DEL':view==='license-note'?'SAVE':view==='license-update'?'UPDATE':'ADD LICENSE' }}</button></div>
        </form>
        <p v-if="notice && view==='rooms'" role="status" class="text-xs text-accent mt-4">{{ notice }}</p>
        <p v-if="error" role="alert" class="text-xs text-red-500 mt-4">{{ error }}</p>
        <p v-if="busy" aria-live="polite" class="sr-only">正在处理操作</p>
      </div>
      <footer class="px-5 py-3 bg-medical-50 border-t border-medical-200 font-mono text-[11px] text-medical-400 flex justify-between"><span>ACCESS VERIFIED</span><span>{{ roomSession.root?'ROOT':'OWNER' }}</span></footer>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue';
import { ShieldCheck, X, Plus, Shuffle, Eye, EyeOff, Pencil, Trash2, ArrowUpToLine } from 'lucide-vue-next';
import PairingCode from './PairingCode.vue';
import { roomsApi } from '../api/rooms';
import { roomSession, clearManager } from '../services/roomSession';
import { registerBackHandler } from '../services/backNavigation';
import { graphemes, validRoomName, validLicenseKey, validLicenseNote, randomLicenseKey } from '../utils/roomValidation';
const props=defineProps({open:Boolean,selecting:Boolean,currentRoomId:{type:String,default:''}});const emit=defineEmits(['close','select']);
const rooms=ref([]),licenses=ref([]),tab=ref('mine'),view=ref('rooms'),working=ref(false),loading=ref(false),error=ref(''),notice=ref(''),roomName=ref(''),licenseKey=ref(''),showKey=ref(false),selectedLicense=ref(null),composing=ref(false);
const busy=computed(()=>working.value||props.selecting);
function chooseRoom(room){if(busy.value)return;error.value='';if(room.id===props.currentRoomId){notice.value='已在该房间中';return;}notice.value='';emit('select',room);}
const licenseNote=ref(''),revealedKeys=ref(new Set());
const toggleKey=id=>{const next=new Set(revealedKeys.value);next.has(id)?next.delete(id):next.add(id);revealedKeys.value=next;};
const tabs=[{id:'all',label:'全部房间'},{id:'mine',label:'我的房间'},{id:'licenses',label:'许可清单'}];
const ownCount=computed(()=>rooms.value.filter(r=>r.ownerId===roomSession.licenseId).length),visibleRooms=computed(()=>tab.value==='all'?rooms.value:rooms.value.filter(r=>r.ownerId===roomSession.licenseId));
const deleting=reactive({id:null,count:0}),actionEffect=reactive({key:'',count:null,sequence:0}),actionPending=ref(false);let deleteTimer,actionEffectTimer,licenseActionTimer,requestId,alive=true;
const newRequestId=()=>[...crypto.getRandomValues(new Uint8Array(16))].map(n=>n.toString(16).padStart(2,'0')).join('');
const resetDelete=()=>{clearTimeout(deleteTimer);deleting.id=null;deleting.count=0;};
const resetAction=()=>{clearTimeout(actionEffectTimer);clearTimeout(licenseActionTimer);actionEffect.key='';actionEffect.count=null;actionPending.value=false;};
const pulseAction=(key,count=null)=>{clearTimeout(actionEffectTimer);actionEffect.key=key;actionEffect.count=count;actionEffect.sequence++;actionEffectTimer=setTimeout(()=>{actionEffect.key='';actionEffect.count=null;},500);};
const close=()=>{if(busy.value)return;resetDelete();resetAction();emit('close');};
const cancelEdit=()=>{view.value='rooms';error.value='';licenseKey.value='';licenseNote.value='';showKey.value=false;composing.value=false;};
const unregisterBack=registerBackHandler(115,()=>{if(!props.open)return false;if(busy.value)return true;if(view.value!=='rooms')cancelEdit();else close();return true;});
const editLicense=(mode,license=null)=>{resetDelete();selectedLicense.value=license;licenseKey.value='';licenseNote.value=license?.note||'';showKey.value=false;error.value='';view.value=mode;};
const licenseAction=(mode,license)=>{if(busy.value||actionPending.value)return;resetDelete();pulseAction(`${mode}:${license.id}`);actionPending.value=true;licenseActionTimer=setTimeout(()=>{actionPending.value=false;editLicense(mode,license);},500);};
const switchTab=async id=>{if(busy.value)return;resetDelete();resetAction();tab.value=id;error.value='';if(id==='licenses')await refresh();};
const fail=e=>{error.value=e.response?.data?.message||e.response?.data?.detail||'操作失败，请重试';if(e.response?.status===403 && /会话|许可已/.test(error.value)){clearManager();emit('close');}};
let refreshGeneration=0, pairingTimer;
function schedulePairingRefresh(data){clearTimeout(pairingTimer);if(!props.open)return;const waits=data.rooms.map(r=>r.nextUpdateAt-r.serverTime).filter(Number.isFinite);if(waits.length)pairingTimer=setTimeout(refresh,Math.max(250,Math.min(...waits)+100));}
async function refresh(){const generation=++refreshGeneration;loading.value=true;try{const data=await roomsApi.list();if(!props.open||generation!==refreshGeneration)return;rooms.value=data.rooms;schedulePairingRefresh(data);if(roomSession.root && tab.value==='licenses'){const list=await roomsApi.licenses();if(props.open&&generation===refreshGeneration)licenses.value=list;}}catch(e){if(props.open&&generation===refreshGeneration)fail(e);}finally{if(generation===refreshGeneration)loading.value=false;}}
async function deleteRoom(id){if(busy.value)return;if(deleting.id!==id){resetDelete();deleting.id=id;deleteTimer=setTimeout(resetDelete,3000);}deleting.count++;pulseAction(`room-delete:${id}`,4-deleting.count);if(deleting.count<3)return;clearTimeout(deleteTimer);working.value=true;try{await new Promise(resolve=>setTimeout(resolve,500));if(!alive||!props.open)return;await roomsApi.delete(id);resetDelete();await refresh();}catch(e){fail(e);resetDelete();}finally{working.value=false;}}
const guardComposition=event=>{if(composing.value||event.isComposing||event.keyCode===229)event.preventDefault();};
async function submitRoom(){if(busy.value||composing.value)return;if(!validRoomName(roomName.value)){error.value='房间名须为 2–16 个可见字符，不含换行或不可见控制字符';return;}working.value=true;try{await roomsApi.create(roomName.value,requestId);roomName.value='';view.value='rooms';notice.value='房间已创建，点击房间名称进入';await refresh();}catch(e){fail(e);}finally{working.value=false;}}
async function submitLicense(){if(busy.value||composing.value)return;if(['license-add','license-update'].includes(view.value)&&!validLicenseKey(licenseKey.value)){error.value='密钥须为 8–16 位大小写英文字母、数字或符号，不含空白';return;}if(view.value==='license-note'&&!validLicenseNote(licenseNote.value)){error.value='备注最多 16 个可见字符，不含换行或不可见控制字符';return;}working.value=true;try{if(view.value==='license-add')await roomsApi.addLicense(licenseKey.value);else if(view.value==='license-update')await roomsApi.updateLicense(selectedLicense.value.id,licenseKey.value);else if(view.value==='license-note')await roomsApi.updateLicenseNote(selectedLicense.value.id,licenseNote.value);else await roomsApi.deleteLicense(selectedLicense.value.id);cancelEdit();await refresh();}catch(e){fail(e);}finally{working.value=false;}}
const refreshVisible=()=>{if(props.open&&!document.hidden)refresh();};
document.addEventListener('visibilitychange',refreshVisible);
window.addEventListener('musicparty:pairing',refreshVisible);
watch(()=>props.open,open=>{resetDelete();resetAction();clearTimeout(pairingTimer);revealedKeys.value=new Set();notice.value='';if(open){view.value='rooms';tab.value=roomSession.root?'all':'mine';error.value='';refresh();}else{refreshGeneration++;loading.value=false;licenses.value=[];rooms.value=[];selectedLicense.value=null;licenseKey.value='';licenseNote.value='';roomName.value='';}});
onBeforeUnmount(()=>{alive=false;refreshGeneration++;clearTimeout(pairingTimer);resetDelete();resetAction();unregisterBack();document.removeEventListener('visibilitychange',refreshVisible);window.removeEventListener('musicparty:pairing',refreshVisible);});
</script>

<style scoped>
.room-manager-panel{display:flex;flex-direction:column;max-height:calc(100dvh - 2rem);overflow:hidden}
.room-manager-panel>header,.room-manager-panel>footer{flex-shrink:0}
.room-manager-body{display:flex;flex-direction:column;min-height:0;flex:1 1 auto}
.room-manager-body>:not(.room-manager-list){flex-shrink:0}
.room-manager-list{min-height:0;flex:1 1 auto;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}
.room-manager-action{position:relative;flex-shrink:0;padding:.5rem;color:rgb(var(--medical-400))}
.room-manager-action:disabled{opacity:.4}
.action-pulse{animation:room-action-pulse .5s ease-in-out}
.action-feedback{animation:room-action-float .5s ease-out both}
@keyframes room-action-pulse{0%,100%{color:inherit}30%,55%{color:rgb(var(--accent))}}
@keyframes room-action-float{0%{opacity:0;transform:translate(-50%,0)}15%{opacity:1}100%{opacity:0;transform:translate(-50%,-18px)}}
@media(min-width:640px){.room-manager-panel{max-height:calc(100dvh - 4rem)}}
@media(max-height:540px){.room-manager-body{display:block;overflow-y:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.room-manager-list{overflow:visible}}
@media(prefers-reduced-motion:reduce){@keyframes room-action-float{0%,100%{opacity:0;transform:translateX(-50%)}15%,75%{opacity:1;transform:translateX(-50%)}}}
</style>
