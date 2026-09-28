<template>
  <div v-if="!passed" class="fixed inset-0 z-[200] bg-medical-50 flex items-center justify-center p-4">
    <div class="bg-surface p-8 shadow-2xl border border-medical-200 w-full max-w-md chamfer-br relative">
      <div class="absolute top-0 left-0 w-2 h-full bg-strong"></div>
      <h2 class="text-2xl font-black text-medical-900 tracking-tighter">
        {{ isSetupMode ? 'CREATE ROOM' : 'SECURITY ACCESS' }}
      </h2>
      <p class="text-xs font-mono text-medical-500 mt-1 mb-6">
        {{ isSetupMode ? 'SET A ROOM NAME AND FOUR-DIGIT PIN.' : `加入 ${userStore.roomName}` }}
      </p>

      <div class="space-y-4">
        <div v-if="isSetupMode">
          <label for="room-name" class="block text-xs font-bold text-medical-500 mb-1">房间名字</label>
          <input id="room-name" v-model="inputRoomName" maxlength="40" placeholder="请输入房间名字"
                 class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent text-medical-900" />
        </div>
        <div>
          <label for="entry-name" class="block text-xs font-bold text-medical-500 mb-1">你的 ID</label>
          <input id="entry-name" v-model="inputName" :readonly="hasSavedName" maxlength="20"
                 placeholder="ENTER CODENAME" @keyup.enter="handleAction"
                 class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-mono text-center text-lg text-medical-900 read-only:opacity-70" />
          <p v-if="hasSavedName" class="text-[10px] text-medical-400 mt-1">入房后可在在线成员列表修改 ID</p>
        </div>
        <div>
          <label class="block text-xs font-bold text-medical-500 mb-2 text-center">{{ isSetupMode ? '设置 4 位数字密码' : '输入 4 位数字密码' }}</label>
          <PinInput v-model="inputPassword" @complete="handleAction" />
        </div>
        <button @click="handleAction" :disabled="loading || !statusReady"
                class="w-full bg-strong text-white font-bold py-3 hover:bg-accent transition-colors disabled:opacity-50">
          {{ loading ? 'VERIFYING...' : (isSetupMode ? 'CREATE ROOM' : 'JOIN ROOM') }}
        </button>
      </div>

      <div v-if="errorMsg" class="mt-4 text-center text-red-500 font-mono text-xs animate-pulse">&gt; ERROR: {{ errorMsg }}</div>
      <button v-if="!statusReady && !loading" @click="checkStatus" class="w-full mt-3 text-xs text-accent underline">重试连接</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import PinInput from './PinInput.vue';
import { authApi } from '../api/auth';
import { STORAGE_KEYS } from '../constants/keys';
import { useUserStore } from '../stores/user';

const emit = defineEmits(['unlocked']);
const userStore = useUserStore();
const savedName = localStorage.getItem(STORAGE_KEYS.USERNAME) || '';
const hasSavedName = !!savedName;
const inputName = ref(savedName);
const inputRoomName = ref('');
const inputPassword = ref('');
const passed = ref(false);
const isSetupMode = ref(false);
const errorMsg = ref('');
const loading = ref(false);
const statusReady = ref(false);

const checkStatus = async () => {
  loading.value = true;
  try {
    const status = await authApi.getStatus();
    isSetupMode.value = !status.isSetup;
    userStore.roomName = status.roomName || '';
    statusReady.value = true;
    errorMsg.value = '';
  } catch (e) {
    errorMsg.value = 'CONNECTION FAILED';
  } finally {
    loading.value = false;
  }
};

const validatedName = () => {
  const name = inputName.value.trim();
  if (!name) { errorMsg.value = '请输入 ID'; return null; }
  if (name.toLowerCase().startsWith('guest') || name.startsWith('游客')) {
    errorMsg.value = '不能使用“游客”作为正式 ID';
    return null;
  }
  return name;
};

const enterRoom = (name) => {
  userStore.prepareEntry(name, inputPassword.value);
  passed.value = true;
  emit('unlocked');
};

const handleAction = async () => {
  if (loading.value || !statusReady.value) return;
  errorMsg.value = '';
  const name = validatedName();
  if (!name) return;
  if (!/^[0-9]{4}$/.test(inputPassword.value)) {
    errorMsg.value = '请输入 4 位数字密码';
    return;
  }
  const roomName = inputRoomName.value.trim();
  if (isSetupMode.value && (!roomName || roomName.length > 40)) {
    errorMsg.value = '请输入 1–40 字的房间名字';
    return;
  }
  loading.value = true;
  try {
    if (isSetupMode.value) {
      await authApi.setup(roomName, inputPassword.value);
      userStore.roomName = roomName;
    } else {
      await authApi.verify(inputPassword.value);
    }
    enterRoom(name);
  } catch (e) {
    errorMsg.value = e.response?.data?.message || (isSetupMode.value ? 'CREATE ROOM FAILED' : 'INVALID PASSWORD');
    inputPassword.value = '';
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD);
  checkStatus();
});
</script>
