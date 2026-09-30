<template>
  <div class="fixed inset-0 z-[100] bg-medical-50 flex items-center justify-center p-4 overflow-y-auto">
    <section class="w-full max-w-md bg-surface border border-medical-200 p-8 shadow-xl chamfer-br relative">
      <div class="absolute inset-y-0 left-0 w-1.5 bg-strong"></div>
      <button type="button" @click="secret" class="text-2xl font-black tracking-tight text-medical-900 text-left">SECURITY ACCESS</button>
      <p class="info-tip text-xs text-medical-400 mt-2 mb-8">输入用户 ID 和四位配对码进入房间</p>
      <label for="entry-name" class="block text-xs font-bold text-medical-500 mb-2">用户 ID</label>
      <input id="entry-name" v-model="name" autocomplete="nickname" maxlength="20" class="w-full bg-medical-50 border border-medical-200 p-3 text-base text-medical-900 focus:border-accent" placeholder="输入用户 ID" @keydown.enter="join">
      <p class="info-tip text-[11px] text-medical-400 mt-2 mb-6">更换 ID 将以新用户进入，仅改名请用原 ID 入房后修改</p>
      <label class="block text-xs font-bold text-medical-500 text-center mb-2">四位配对码</label>
      <PinInput v-model="code" @complete="join" />
      <div class="flex gap-2 mt-6">
        <button type="button" @click="join" :disabled="busy || !ready" class="flex-1 bg-accent text-white py-3 font-bold disabled:opacity-50">{{ busy ? 'VERIFYING...' : 'JOIN ROOM' }}</button>
        <button type="button" @click="showManager=true" :disabled="!roomSession.managerToken" class="manage-access bg-strong text-white h-12 w-12 flex items-center justify-center disabled:opacity-40" :class="{ unlocked: roomSession.managerToken }" aria-label="管理房间">
          <Unlock v-if="roomSession.managerToken" class="w-4 h-4 manage-icon" /><Lock v-else class="w-4 h-4" /><span class="manage-text text-xs font-bold">MANAGE</span>
        </button>
      </div>
      <p v-if="ready && roomCount===0" class="info-tip text-[11px] text-medical-400 mt-4">暂无房间，房主可通过许可验证后创建</p>
      <p v-if="error" role="alert" class="text-xs text-red-500 mt-4">{{ error }}</p>
      <button v-if="!ready" type="button" @click="initialize" class="text-xs text-accent mt-4">重试连接</button>
    </section>
    <div v-if="showLicense" class="fixed inset-0 z-[110] bg-overlay/60 backdrop-blur-sm flex items-center justify-center p-4">
      <section class="w-full max-w-sm bg-surface border border-medical-200 chamfer-br">
        <header class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between"><h2 class="font-mono font-bold text-sm flex items-center gap-2"><KeyRound class="w-4 h-4" /> LICENSE VERIFY</h2><button @click="showLicense=false" aria-label="关闭许可验证"><X class="w-4 h-4" /></button></header>
        <form @submit.prevent="verify" class="p-6">
          <label for="entry-license" class="text-xs text-medical-500 block mb-2">许可密钥</label>
          <input id="entry-license" v-model="key" type="password" autocomplete="off" class="w-full p-3 border border-medical-200 bg-medical-50 text-base" placeholder="ENTER LICENSE KEY">
          <p class="info-tip text-[11px] text-medical-400 mt-2">验证后可管理所属房间，无需配对码</p>
          <p v-if="licenseError" role="alert" class="text-xs text-red-500 mt-4">{{ licenseError }}</p>
          <button :disabled="busy" class="w-full bg-strong text-white py-3 mt-6 font-bold disabled:opacity-50">{{ busy?'VERIFYING...':'VERIFY LICENSE' }}</button>
        </form>
      </section>
    </div>
    <RoomManager :open="showManager" @close="showManager=false" @select="ownerEntry" />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { Lock, Unlock, KeyRound, X } from 'lucide-vue-next';
import PinInput from './PinInput.vue';
import RoomManager from './RoomManager.vue';
import { roomsApi } from '../api/rooms';
import { authApi } from '../api/auth';
import { useUserStore } from '../stores/user';
import { roomSession, saveManager, clearManager, selectRoom } from '../services/roomSession';
import { registerBackHandler } from '../services/backNavigation';

const emit=defineEmits(['unlocked']);
const user=useUserStore();
const name=ref(localStorage.getItem('mp_username') || ''),code=ref(''),key=ref('');
const busy=ref(false),ready=ref(false),error=ref(''),licenseError=ref(''),roomCount=ref(0),showLicense=ref(false),showManager=ref(false);
let secretClicks=[];
const unregisterBack=registerBackHandler(110,()=>{if(!showLicense.value)return false;showLicense.value=false;key.value='';return true;});
onBeforeUnmount(unregisterBack);
const secret=()=>{const now=Date.now();secretClicks=secretClicks.filter(t=>now-t<=2000);secretClicks.push(now);if(secretClicks.length>=5){secretClicks=[];licenseError.value='';showLicense.value=true;}};
const validName=()=>{const value=name.value.trim();if(!value || /^guest/i.test(value) || value.startsWith('游客')) {error.value='请输入有效的用户 ID，不能使用游客名称';return null;}return value;};
const enter=(room,token,owner)=>{const value=validName();if(!value)return;user.prepareEntry(value,'');selectRoom(room,token,owner);user.roomName=room.name;user.isAuthPassed=true;emit('unlocked');};
async function join(){if(busy.value||!ready.value)return;error.value='';if(!validName())return;if(!/^[0-9]{4}$/.test(code.value)){error.value='请输入四位配对码';return;}busy.value=true;try{const result=await roomsApi.join(code.value);localStorage.setItem('mp_admission_'+result.room.id,JSON.stringify({token:result.token,expiresAt:result.expiresAt}));enter(result.room,result.token,false);}catch(e){error.value=e.response?.data?.message||'配对码无效或已更新';code.value='';}finally{busy.value=false;}}
async function verify(){if(busy.value)return;busy.value=true;licenseError.value='';try{saveManager(await roomsApi.login(key.value));key.value='';showLicense.value=false;}catch(e){licenseError.value=e.response?.data?.message||'许可验证失败';}finally{busy.value=false;}}
async function ownerEntry(room){if(!validName()){showManager.value=false;return;}busy.value=true;try{const current=await roomsApi.manage(room.id);showManager.value=false;enter(current,'',true);}catch(e){error.value=e.response?.data?.message||'无法进入房间';}finally{busy.value=false;}}
async function initialize(){ready.value=false;try{const result=await authApi.getStatus();roomCount.value=result.roomCount;ready.value=true;error.value='';if(roomSession.managerToken)try{await roomsApi.session();}catch{clearManager();}
  if(!user.justReturned){const id=sessionStorage.getItem('mp_active_room')||localStorage.getItem('mp_last_room');const saved=JSON.parse(localStorage.getItem('mp_admission_'+id)||'null');if(id&&validName()&&saved?.expiresAt>Date.now()){try{const restored=await roomsApi.resume(id,saved.token);enter(restored.room,saved.token,false);}catch{localStorage.removeItem('mp_admission_'+id);}}else if(id&&roomSession.managerToken&&name.value.trim()){try{enter(await roomsApi.manage(id),'',true);}catch{}}error.value='';}
}catch{error.value='服务连接失败';}}
onMounted(initialize);
</script>

<style scoped>
.manage-access{transition:width .25s;flex-shrink:0}.manage-text{display:none}
@media(hover:hover){.manage-access.unlocked:hover{width:112px}.manage-access.unlocked:hover .manage-icon{display:none}.manage-access.unlocked:hover .manage-text{display:block}}
@media(hover:none){.manage-access.unlocked{width:108px}.manage-access.unlocked .manage-icon{display:none}.manage-access.unlocked .manage-text{display:block}}
</style>
