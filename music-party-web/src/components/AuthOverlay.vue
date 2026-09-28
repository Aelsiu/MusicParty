<template>
  <div v-if="!passed" class="fixed inset-0 z-[200] bg-medical-50 flex items-center justify-center p-4">
    <div class="bg-surface p-8 shadow-2xl border border-medical-200 w-full max-w-md chamfer-br relative">
      <div class="absolute top-0 left-0 w-2 h-full bg-strong"></div>

      <div class="mb-6">
        <h2 class="text-2xl font-black text-medical-900 tracking-tighter">
          {{ isSetupMode ? 'INITIALIZE SYSTEM' : 'SECURITY ACCESS' }}
        </h2>
        <p class="text-xs font-mono text-medical-500 mt-1">
          {{ isSetupMode ? 'PLEASE CONFIGURE ROOM ACCESS.' : 'ENTER YOUR ID TO JOIN THE ROOM.' }}
        </p>
      </div>

      <div class="space-y-4">
        <div>
          <label for="entry-name" class="block text-xs font-bold text-medical-500 mb-1">你的 ID</label>
          <input
              id="entry-name"
              v-model="inputName"
              :readonly="hasSavedName"
              maxlength="20"
              placeholder="ENTER CODENAME"
              @keyup.enter="handleAction"
              class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-mono text-center text-lg text-medical-900 read-only:opacity-70"
          />
          <p v-if="hasSavedName" class="text-[10px] text-medical-400 mt-1">入房后可在在线成员列表修改 ID</p>
        </div>

        <input
            v-if="needsPassword"
            v-model="inputPassword"
            type="password"
            :placeholder="isSetupMode ? 'SET NEW PASSWORD' : 'INPUT PASSWORD'"
            @keyup.enter="handleAction"
            class="w-full bg-medical-50 border border-medical-200 p-3 outline-none focus:border-accent font-mono text-center tracking-widest text-lg text-medical-900"
        />

        <button
            v-if="!isSetupMode || setupType === 'password'"
            @click="handleAction"
            :disabled="loading || !statusReady"
            class="w-full bg-strong text-white font-bold py-3 hover:bg-accent transition-colors disabled:opacity-50"
        >
          {{ loading ? 'VERIFYING...' : (isSetupMode ? 'CONFIRM PASSWORD' : 'JOIN ROOM') }}
        </button>

        <div v-if="isSetupMode && setupType === 'initial'" class="space-y-3">
          <button
              @click="setupType = 'password'"
              :disabled="!statusReady"
              class="w-full bg-strong text-white font-bold py-3 hover:bg-accent transition-colors chamfer-br"
          >
            SET PASSWORD PROTECTION
          </button>
          <div class="relative flex py-2 items-center">
            <div class="flex-grow border-t border-medical-200"></div>
            <span class="flex-shrink-0 mx-4 text-medical-300 text-xs font-mono">OR</span>
            <div class="flex-grow border-t border-medical-200"></div>
          </div>
          <button
              @click="setupNoPassword"
              :disabled="loading || !statusReady"
              class="w-full bg-surface border border-medical-300 text-medical-500 font-bold py-3 hover:bg-medical-100 transition-colors hover:text-medical-900 disabled:opacity-50"
          >
            NO PASSWORD (PUBLIC)
          </button>
        </div>

        <button
            v-if="isSetupMode && setupType === 'password'"
            @click="setupType = 'initial'"
            class="w-full text-xs text-medical-400 hover:text-medical-900 mt-2 underline"
        >
          &lt; BACK
        </button>
      </div>

      <div v-if="errorMsg" class="mt-4 text-center text-red-500 font-mono text-xs animate-pulse">
        &gt; ERROR: {{ errorMsg }}
      </div>
      <button v-if="!statusReady && !loading" @click="checkStatus" class="w-full mt-3 text-xs text-accent underline">重试连接</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { authApi } from '../api/auth';
import { STORAGE_KEYS } from '../constants/keys';
import { useUserStore } from '../stores/user';

const emit = defineEmits(['unlocked']);
const userStore = useUserStore();
const savedName = localStorage.getItem(STORAGE_KEYS.USERNAME) || '';
const hasSavedName = !!savedName;
const inputName = ref(savedName);
const passed = ref(false);
const isSetupMode = ref(false);
const setupType = ref('initial');
const hasProtection = ref(true);
const needsPassword = computed(() => isSetupMode.value ? setupType.value === 'password' : hasProtection.value);
const inputPassword = ref('');
const errorMsg = ref('');
const loading = ref(false);
const statusReady = ref(false);

const validatedName = () => {
  const name = inputName.value.trim();
  if (!name) {
    errorMsg.value = '请输入 ID';
    return null;
  }
  if (name.toLowerCase().startsWith('guest') || name.startsWith('游客')) {
    errorMsg.value = '不能使用“游客”作为正式 ID';
    return null;
  }
  return name;
};

const enterRoom = (name, password) => {
  userStore.prepareEntry(name, password);
  passed.value = true;
  emit('unlocked');
};

const checkStatus = async () => {
  loading.value = true;
  try {
    const { isSetup, hasProtection: protectedRoom } = await authApi.getStatus();
    isSetupMode.value = !isSetup;
    hasProtection.value = protectedRoom;
    statusReady.value = true;
    errorMsg.value = '';
  } catch (e) {
    console.error('Auth Status Error:', e);
    errorMsg.value = 'CONNECTION FAILED';
  } finally {
    loading.value = false;
  }
};

const verify = async (name) => {
  loading.value = true;
  try {
    await authApi.verify(inputPassword.value);
    enterRoom(name, inputPassword.value);
  } catch (e) {
    errorMsg.value = 'INVALID PASSWORD';
    inputPassword.value = '';
  } finally {
    loading.value = false;
  }
};

const performSetup = async (password) => {
  const name = validatedName();
  if (!name) return;
  loading.value = true;
  try {
    await authApi.setup(password);
    enterRoom(name, password);
  } catch (e) {
    errorMsg.value = 'SETUP FAILED';
  } finally {
    loading.value = false;
  }
};

const setupNoPassword = () => performSetup('');

const handleAction = () => {
  if (loading.value || !statusReady.value) return;
  errorMsg.value = '';
  const name = validatedName();
  if (!name) return;
  if (isSetupMode.value) {
    if (!inputPassword.value) {
      errorMsg.value = 'PASSWORD CANNOT BE EMPTY';
      return;
    }
    performSetup(inputPassword.value);
  } else if (hasProtection.value) {
    verify(name);
  } else {
    enterRoom(name, '');
  }
};

onMounted(() => {
  localStorage.removeItem(STORAGE_KEYS.ROOM_PASSWORD);
  checkStatus();
});
</script>
