<template>
  <div v-if="open" class="fixed inset-0 z-[115] bg-overlay/60 backdrop-blur-sm flex items-start justify-center p-4 sm:p-8 overflow-y-auto">
    <section class="w-full max-w-2xl bg-surface border border-medical-200 shadow-2xl chamfer-br my-auto">
      <header class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between items-center gap-3">
        <h2 class="font-mono font-bold tracking-widest text-sm flex items-center gap-2"><ShieldCheck class="w-4 h-4" /> {{ view==='create'?'CREATE ROOM':view.startsWith('license-')?'LICENSE MANAGER':'ROOM MANAGER' }}</h2>
        <button @click="close" aria-label="关闭房间管理" class="p-2"><X class="w-4 h-4" /></button>
      </header>
      <div class="p-5 sm:p-7">
        <template v-if="view==='rooms'">
          <div class="flex justify-between text-xs text-medical-500 gap-3 mb-5"><span>{{ roomSession.root?'最高许可':'所属房间' }} / {{ roomSession.licenseId }}</span><span class="font-mono shrink-0">{{ ownCount }} / 9</span></div>
          <div v-if="roomSession.root" class="flex gap-2 flex-wrap mb-5">
            <button v-for="item in tabs" :key="item.id" @click="switchTab(item.id)" :class="tab===item.id?'bg-strong text-white':'text-medical-500'" class="border border-medical-200 px-3 py-2 text-xs">{{ item.label }}</button>
          </div>
          <template v-if="tab!=='licenses'">
            <div class="border-t border-medical-200">
              <div v-for="room in visibleRooms" :key="room.id" class="flex items-center gap-3 py-4 border-b border-medical-200">
                <button @click="emit('select',room)" :disabled="busy" class="text-left flex-1 min-w-0"><span class="block text-base font-bold break-words">{{ room.name }}</span><span class="block text-[11px] text-medical-400 font-mono mt-1">ID {{ room.id }}<template v-if="roomSession.root && tab==='all'"> / {{ room.ownerId }}</template></span></button>
                <button @click="deleteRoom(room.id)" :disabled="busy" :aria-label="`删除房间 ${room.name}，三秒内连续点击三次`" :class="{ shake: deleting.id===room.id && deleting.count }" class="border border-medical-200 px-5 h-11 text-accent font-mono text-xs">{{ deleting.id===room.id?`DEL ${deleting.count}/3`:'DEL' }}</button>
              </div>
            </div>
            <p v-if="!visibleRooms.length && !loading" class="py-7 text-center text-xs text-medical-400">暂无房间，创建后即可邀请成员加入</p>
            <button @click="view='create';error='';requestId=newRequestId()" :disabled="busy||ownCount>=9" class="mt-6 w-full bg-accent text-white font-bold py-3 flex items-center justify-center gap-2 disabled:opacity-40"><Plus class="w-4 h-4" /> CREATE ROOM</button>
            <button @click="refresh" :disabled="loading" class="block mx-auto text-[11px] text-medical-400 mt-1 p-2">{{ loading?'LOADING...':'REFRESH' }}</button>
          </template>
          <template v-else>
            <div class="flex justify-between text-xs text-medical-400 mb-4"><span>普通许可清单</span><span class="font-mono">{{ licenses.length }} LICENSES</span></div>
            <div class="border-t border-medical-200"><div v-for="license in licenses" :key="license.id" class="flex items-center gap-2 flex-wrap py-4 border-b border-medical-200">
              <div class="flex-1 min-w-0"><span class="block font-bold break-words">{{ license.id }}</span><span class="block text-[11px] text-medical-400 mt-2">{{ license.roomCount }} 个所属房间 · 密钥不回显</span></div>
              <button @click="editLicense('license-update',license)" class="border border-medical-200 px-4 h-11 text-xs font-bold">UPDATE</button><button @click="editLicense('license-delete',license)" class="border border-medical-200 px-5 h-11 text-accent font-mono text-xs">DEL</button>
            </div></div>
            <button @click="editLicense('license-add')" class="w-full bg-accent text-white font-bold py-3 mt-6 flex items-center justify-center gap-2"><Plus class="w-4 h-4" /> ADD LICENSE</button>
            <button @click="refresh" :disabled="loading" class="block mx-auto text-[11px] text-medical-400 mt-1 p-2">{{ loading?'LOADING...':'REFRESH' }}</button>
            <p class="text-[11px] text-medical-400 mt-4">最高许可只能通过服务端配置文件更换，更换后需重启</p>
          </template>
        </template>
        <form v-else-if="view==='create'" @submit.prevent="submitRoom">
          <label for="new-room-name" class="text-xs font-bold text-medical-500 block mb-2">房间名</label>
          <input id="new-room-name" v-model="roomName" type="text" inputmode="text" autocomplete="off" :spellcheck="false" @compositionstart="composing=true" @compositionend="composing=false" @keydown.enter="guardComposition" class="w-full bg-medical-50 border border-medical-200 px-3 py-3 text-base" placeholder="名称可重复">
          <p class="text-[11px] text-medical-400 mt-2">{{ graphemes(roomName).length }} / 16 · 中文与 emoji 均可</p>
          <div class="flex gap-2 mt-6"><button type="button" @click="cancelEdit" class="flex-1 border border-medical-200 py-3 text-xs font-bold">CANCEL</button><button :disabled="busy||composing" class="flex-1 bg-accent text-white py-3 text-xs font-bold disabled:opacity-40">{{ busy?'CREATING...':'CREATE ROOM' }}</button></div>
        </form>
        <form v-else @submit.prevent="submitLicense">
          <template v-if="view==='license-delete'"><p class="font-bold break-words">{{ selectedLicense.id }}</p><p class="text-xs text-medical-500 mt-3">删除该许可将同时删除其 {{ selectedLicense.roomCount }} 个房间，关联连接、直播和登录任务将一并结束</p></template>
          <template v-else>
            <label for="license-key" class="text-xs font-bold text-medical-500 block mb-2">{{ view==='license-update'?'新的许可密钥':'许可密钥' }}</label>
            <div class="flex gap-2"><input id="license-key" v-model="licenseKey" :type="showKey?'text':'password'" autocomplete="off" class="min-w-0 flex-1 bg-medical-50 border border-medical-200 px-3 py-3 text-base"><button type="button" @click="showKey=!showKey" class="border border-medical-200 px-3 text-xs" :aria-label="showKey?'隐藏密钥':'显示密钥'"><Eye v-if="!showKey" class="w-4 h-4"/><EyeOff v-else class="w-4 h-4"/></button></div>
            <button type="button" @click="licenseKey=randomLicenseKey();showKey=true" class="text-accent text-xs mt-3 flex items-center gap-2"><Shuffle class="w-4 h-4"/> RANDOM</button>
            <p class="text-[11px] text-medical-400 mt-3">8–16 位大小写英文字母、数字或符号，不含空白</p>
            <p v-if="view==='license-update'" class="text-[11px] text-medical-400 mt-3">所属房间保留，旧密钥和旧管理会话立即失效</p>
          </template>
          <div class="flex gap-2 mt-6"><button type="button" @click="cancelEdit" class="flex-1 border border-medical-200 py-3 text-xs font-bold">CANCEL</button><button :disabled="busy" :class="view==='license-delete'?'border border-medical-200 text-accent':'bg-accent text-white'" class="flex-1 py-3 text-xs font-bold disabled:opacity-40">{{ busy?'SAVING...':view==='license-delete'?'DEL':view==='license-update'?'UPDATE':'ADD LICENSE' }}</button></div>
        </form>
        <p v-if="error" role="alert" class="text-xs text-red-500 mt-4">{{ error }}</p>
        <p v-if="busy" aria-live="polite" class="sr-only">正在处理操作</p>
      </div>
      <footer class="px-5 py-3 bg-medical-50 border-t border-medical-200 font-mono text-[11px] text-medical-400 flex justify-between"><span>ACCESS VERIFIED</span><span>{{ roomSession.root?'ROOT':'OWNER' }}</span></footer>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue';
import { ShieldCheck, X, Plus, Shuffle, Eye, EyeOff } from 'lucide-vue-next';
import { roomsApi } from '../api/rooms';
import { roomSession, clearManager } from '../services/roomSession';
import { registerBackHandler } from '../services/backNavigation';
import { graphemes, validRoomName, validLicenseKey, randomLicenseKey } from '../utils/roomValidation';
const props=defineProps({open:Boolean});const emit=defineEmits(['close','select']);
const rooms=ref([]),licenses=ref([]),tab=ref('mine'),view=ref('rooms'),busy=ref(false),loading=ref(false),error=ref(''),roomName=ref(''),licenseKey=ref(''),showKey=ref(false),selectedLicense=ref(null),composing=ref(false);
const tabs=[{id:'all',label:'全部房间'},{id:'mine',label:'我的房间'},{id:'licenses',label:'许可清单'}];
const ownCount=computed(()=>rooms.value.filter(r=>r.ownerId===roomSession.licenseId).length),visibleRooms=computed(()=>tab.value==='all'?rooms.value:rooms.value.filter(r=>r.ownerId===roomSession.licenseId));
const deleting=reactive({id:null,count:0});let deleteTimer,requestId;
const newRequestId=()=>[...crypto.getRandomValues(new Uint8Array(16))].map(n=>n.toString(16).padStart(2,'0')).join('');
const resetDelete=()=>{clearTimeout(deleteTimer);deleting.id=null;deleting.count=0;};
const close=()=>{if(busy.value)return;resetDelete();emit('close');};
const cancelEdit=()=>{view.value='rooms';error.value='';licenseKey.value='';showKey.value=false;};
const unregisterBack=registerBackHandler(115,()=>{if(!props.open)return false;if(busy.value)return true;if(view.value!=='rooms')cancelEdit();else close();return true;});
const editLicense=(mode,license=null)=>{resetDelete();selectedLicense.value=license;licenseKey.value='';showKey.value=false;error.value='';view.value=mode;};
const switchTab=async id=>{resetDelete();tab.value=id;error.value='';if(id==='licenses')await refresh();};
const fail=e=>{error.value=e.response?.data?.message||e.response?.data?.detail||'操作失败，请重试';if(e.response?.status===403 && /会话|许可已/.test(error.value)){clearManager();emit('close');}};
async function refresh(){loading.value=true;try{const data=await roomsApi.list();rooms.value=data.rooms;if(roomSession.root && tab.value==='licenses')licenses.value=await roomsApi.licenses();}catch(e){fail(e);}finally{loading.value=false;}}
async function deleteRoom(id){if(busy.value)return;if(deleting.id!==id){resetDelete();deleting.id=id;deleteTimer=setTimeout(resetDelete,3000);}deleting.count++;if(deleting.count<3)return;busy.value=true;try{await roomsApi.delete(id);resetDelete();await refresh();}catch(e){fail(e);resetDelete();}finally{busy.value=false;}}
const guardComposition=event=>{if(composing.value||event.isComposing||event.keyCode===229)event.preventDefault();};
async function submitRoom(){if(busy.value||composing.value)return;if(!validRoomName(roomName.value)){error.value='房间名须为 2–16 个可见字符，不含换行或不可见控制字符';return;}busy.value=true;try{const room=await roomsApi.create(roomName.value,requestId);roomName.value='';view.value='rooms';await refresh();emit('select',room);}catch(e){fail(e);}finally{busy.value=false;}}
async function submitLicense(){if(busy.value)return;if(view.value!=='license-delete'&&!validLicenseKey(licenseKey.value)){error.value='密钥须为 8–16 位大小写英文字母、数字或符号，不含空白';return;}busy.value=true;try{if(view.value==='license-add')await roomsApi.addLicense(licenseKey.value);else if(view.value==='license-update')await roomsApi.updateLicense(selectedLicense.value.id,licenseKey.value);else await roomsApi.deleteLicense(selectedLicense.value.id);cancelEdit();await refresh();}catch(e){fail(e);}finally{busy.value=false;}}
watch(()=>props.open,open=>{resetDelete();if(open){view.value='rooms';tab.value=roomSession.root?'all':'mine';error.value='';refresh();}else{licenseKey.value='';roomName.value='';}});
onBeforeUnmount(()=>{resetDelete();unregisterBack();});
</script>

<style scoped>
.shake{animation:room-shake .2s}@keyframes room-shake{25%,75%{transform:translateX(-3px)}50%{transform:translateX(3px)}}@media(prefers-reduced-motion:reduce){.shake{animation:none}}
</style>
