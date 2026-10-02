<template>
  <div class="fixed inset-0 z-[100] bg-medical-50 flex items-center justify-center p-4 overflow-y-auto">
    <section class="w-full max-w-md bg-surface border border-medical-200 p-8 shadow-xl chamfer-br relative">
      <div class="absolute inset-y-0 left-0 w-1.5 bg-strong"></div>
      <h1 class="text-2xl font-black tracking-tight text-medical-900 mb-8">{{ roomId ? 'NEED CODE' : 'SECURITY ACCESS' }}</h1>
      <div v-if="roomId" class="mb-6">
        <p class="font-bold text-accent break-words">{{ targetRoom?.name || 'MUSIC PARTY' }}</p>
        <p class="font-mono text-xs text-medical-400 mt-1">ROOM ID / {{ roomId }}</p>
      </div>
      <p v-if="!ready && !error" role="status" class="font-mono text-xs text-medical-400">CONNECTING...</p>
      <template v-else-if="ready">
        <label for="entry-name" class="block text-xs font-bold text-medical-500 mb-2">用户 ID<template v-if="roomId">（可选）</template></label>
        <input id="entry-name" v-model="name" autocomplete="nickname" maxlength="20" class="w-full bg-medical-50 border border-medical-200 p-3 text-base text-medical-900 focus:border-accent" :placeholder="roomId ? '留空以游客身份进入' : '输入用户 ID'" @keydown.enter="join">
        <p class="info-tip text-[11px] text-medical-400 mt-2 mb-6">更换 ID 将以新用户进入，仅改名请用原 ID 入房后修改</p>
        <label class="block text-xs font-bold text-medical-500 text-center mb-2">输入配对码</label>
        <PinInput :model-value="code" label="输入配对码" @update:model-value="setCode" @complete="join" />
        <template v-if="roomId">
          <div class="flex items-center gap-3 my-5" aria-hidden="true"><div class="h-px flex-1 bg-medical-200"></div><span class="font-mono text-xs text-medical-400">OR</span><div class="h-px flex-1 bg-medical-200"></div></div>
          <label for="room-license" class="block text-xs font-bold text-medical-500 mb-2">许可密钥</label>
          <input id="room-license" v-model="key" @input="code = ''" type="password" autocomplete="off" placeholder="ENTER LICENSE KEY" class="w-full bg-medical-50 border border-medical-200 p-3 text-base focus:border-accent" @keydown.enter="join">
          <p class="info-tip text-[11px] text-medical-400 mt-2">使用所属许可或最高许可，以管理身份进入</p>
        </template>
        <div class="flex gap-2 mt-6">
          <button type="button" @click="join" :disabled="busy" class="flex-1 bg-accent text-white py-3 font-bold disabled:opacity-50">{{ busy ? 'VERIFYING...' : 'JOIN ROOM' }}</button>
          <button v-if="!roomId" type="button" @click="openManagement" :disabled="busy" class="manage-access bg-strong text-white h-12 w-12 flex items-center justify-center disabled:opacity-40" :class="{ unlocked: roomSession.managerToken }" aria-label="验证许可并管理房间">
            <Unlock v-if="roomSession.managerToken" class="w-4 h-4 manage-icon" /><Lock v-else class="w-4 h-4" /><span class="manage-text text-xs font-bold">MANAGE</span>
          </button>
        </div>
        <p v-if="!roomId && roomCount === 0" class="info-tip text-[11px] text-medical-400 mt-4">暂无房间，房主可通过许可验证后创建</p>
      </template>
      <p v-if="error" role="alert" class="text-xs text-red-500 mt-4">{{ error }}</p>
      <button v-if="!ready && error" type="button" @click="initialize" class="text-xs text-accent mt-4">重试连接</button>
      <button v-if="roomId" type="button" @click="returnRoot" :disabled="busy" class="block mx-auto font-mono text-xs text-accent p-2 mt-4">RETURN</button>
    </section>
    <div v-if="showLicense" class="fixed inset-0 z-[110] bg-overlay/60 backdrop-blur-sm flex items-center justify-center p-4">
      <section class="w-full max-w-sm bg-surface border border-medical-200 chamfer-br">
        <header class="p-4 bg-medical-50 border-b border-medical-200 flex justify-between"><h2 class="font-mono font-bold text-sm flex items-center gap-2"><KeyRound class="w-4 h-4" /> LICENSE VERIFY</h2><button @click="closeLicense" :disabled="busy" aria-label="关闭许可验证"><X class="w-4 h-4" /></button></header>
        <form @submit.prevent="verify" class="p-6">
          <label for="entry-license" class="text-xs text-medical-500 block mb-2">许可密钥</label>
          <input id="entry-license" ref="licenseInput" v-model="key" type="password" autocomplete="off" class="w-full p-3 border border-medical-200 bg-medical-50 text-base" placeholder="ENTER LICENSE KEY">
          <p class="info-tip text-[11px] text-medical-400 mt-2">验证后可管理所属房间，无需配对码</p>
          <p v-if="licenseError" role="alert" class="text-xs text-red-500 mt-4">{{ licenseError }}</p>
          <button :disabled="busy" class="w-full bg-strong text-white py-3 mt-6 font-bold disabled:opacity-50">{{ busy ? 'VERIFYING...' : 'VERIFY LICENSE' }}</button>
        </form>
      </section>
    </div>
    <RoomManager :open="showManager" :selecting="busy" @close="showManager = false" @select="ownerEntry" />
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { Lock, Unlock, KeyRound, X } from 'lucide-vue-next';
import PinInput from './PinInput.vue';
import RoomManager from './RoomManager.vue';
import { roomsApi } from '../api/rooms';
import { authApi } from '../api/auth';
import { useUserStore } from '../stores/user';
import { roomSession, saveManager, clearManager, selectRoom } from '../services/roomSession';
import { resolveRoomEntry } from '../services/roomEntry';
import { navigateRoom } from '../services/roomRoute';
import { registerBackHandler } from '../services/backNavigation';

const props = defineProps({ roomId: { type: String, default: '' } });
const emit = defineEmits(['unlocked']);
const user = useUserStore();
const name = ref(localStorage.getItem('mp_username') || ''), code = ref(''), key = ref('');
const busy = ref(false), ready = ref(false), error = ref(''), licenseError = ref(''), roomCount = ref(0);
const showLicense = ref(false), showManager = ref(false), targetRoom = ref(null), licenseInput = ref(null);
let alive = true, generation = 0;
const current = request => alive && request === generation;
const unregisterBack = registerBackHandler(110, () => {
  if (!showLicense.value) return false;
  if (!busy.value) closeLicense();
  return true;
});
onBeforeUnmount(() => { alive = false; generation++; unregisterBack(); });

function closeLicense() { if (busy.value) return; showLicense.value = false; key.value = ''; licenseError.value = ''; }
function openManagement() {
  if (busy.value) return;
  if (roomSession.managerToken) { showManager.value = true; return; }
  key.value = ''; licenseError.value = ''; showLicense.value = true;
  nextTick(() => licenseInput.value?.focus());
}
const setCode = value => { code.value = value; if (value) key.value = ''; };
const returnRoot = () => navigateRoom();
function entryName(required = !props.roomId) {
  const value = name.value.trim();
  if (!value && !required) return '游客';
  if (!value || /^guest/i.test(value) || value.startsWith('游客')) {
    error.value = '请输入有效的用户 ID，不能使用游客名称'; return null;
  }
  return value;
}
function enter(room, token = '', owner = false, value = entryName()) {
  if (!alive || !value) return;
  user.prepareEntry(value, ''); selectRoom(room, token, owner);
  user.roomName = room.name; user.isAuthPassed = true; emit('unlocked');
}
function remember(result) {
  localStorage.setItem('mp_admission_' + result.room.id, JSON.stringify({ token: result.token, expiresAt: result.expiresAt }));
}
async function join() {
  if (busy.value || !ready.value) return;
  error.value = '';
  const value = entryName(); if (!value) return;
  if (props.roomId && key.value) { await verifyRoomLicense(value); return; }
  if (!/^[0-9]{4}$/.test(code.value)) { error.value = '请输入四位配对码'; return; }
  busy.value = true; const request = ++generation;
  try {
    const result = props.roomId ? await roomsApi.joinRoom(props.roomId, code.value) : await roomsApi.join(code.value);
    if (!current(request)) return;
    remember(result); enter(result.room, result.token, false, value);
  } catch (e) {
    if (current(request)) { error.value = e.response?.data?.message || '配对码无效或已更新'; code.value = ''; }
  } finally { if (current(request)) busy.value = false; }
}
async function verifyRoomLicense(value) {
  busy.value = true; const request = ++generation;
  try {
    const session = await roomsApi.login(key.value);
    if (!current(request)) return;
    const room = await roomsApi.manageAs(props.roomId, session.token);
    if (!current(request)) return;
    saveManager(session); key.value = ''; enter(room, '', true, value);
  } catch (e) { if (current(request)) error.value = e.response?.data?.message || '许可验证失败'; }
  finally { if (current(request)) busy.value = false; }
}
async function verify() {
  if (busy.value || !key.value) return;
  busy.value = true; licenseError.value = ''; const request = ++generation;
  try {
    const session = await roomsApi.login(key.value);
    if (!current(request)) return;
    saveManager(session); key.value = ''; showLicense.value = false; showManager.value = true;
  } catch (e) { if (current(request)) licenseError.value = e.response?.data?.message || '许可验证失败'; }
  finally { if (current(request)) busy.value = false; }
}
async function ownerEntry(room) {
  if (busy.value) return;
  const value = entryName(); if (!value) { showManager.value = false; return; }
  busy.value = true; const request = ++generation;
  try {
    const selected = await roomsApi.manage(room.id);
    if (!current(request)) return;
    showManager.value = false; enter(selected, '', true, value);
  } catch (e) { if (current(request)) error.value = e.response?.data?.message || '无法进入房间'; }
  finally { if (current(request)) busy.value = false; }
}
async function initialize() {
  ready.value = false; error.value = ''; const request = ++generation;
  try {
    const status = await authApi.getStatus(); if (!current(request)) return;
    roomCount.value = status.roomCount;
    if (roomSession.managerToken) {
      try { await roomsApi.session(); }
      catch (e) { if (!current(request)) return; if (e.response?.status === 403) clearManager(); else throw e; }
    }
    if (!current(request)) return;
    let admission = null;
    try { admission = JSON.parse(localStorage.getItem('mp_admission_' + props.roomId) || 'null'); } catch { /* Discard malformed local state. */ }
    const resolved = await resolveRoomEntry(props.roomId, {
      api: roomsApi, managerToken: roomSession.managerToken, admission,
      discardAdmission: () => localStorage.removeItem('mp_admission_' + props.roomId)
    });
    if (!current(request)) return;
    if (resolved.kind === 'member' || resolved.kind === 'owner') {
      if (resolved.kind === 'member') remember(resolved);
      const value = name.value.trim();
      enter(resolved.room, resolved.token || '', resolved.kind === 'owner', value && !/^guest/i.test(value) && !value.startsWith('游客') ? value : '游客');
    } else { targetRoom.value = resolved.room || null; ready.value = true; }
  } catch (e) { if (current(request)) error.value = e.response?.data?.message || '服务连接失败'; }
}
onMounted(initialize);
</script>

<style scoped>
.manage-access{transition:width .25s;flex-shrink:0}.manage-text{display:none}
@media(hover:hover){.manage-access.unlocked:hover{width:112px}.manage-access.unlocked:hover .manage-icon{display:none}.manage-access.unlocked:hover .manage-text{display:block}}
@media(hover:none){.manage-access.unlocked{width:108px}.manage-access.unlocked .manage-icon{display:none}.manage-access.unlocked .manage-text{display:block}}
</style>
