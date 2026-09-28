<template>
  <div v-if="isOpen" class="fixed inset-0 z-[70] bg-overlay/80 backdrop-blur-sm flex items-center justify-center p-0 md:p-4" role="dialog" aria-modal="true" aria-label="设置">
    <div class="w-full max-w-4xl h-full md:h-[80vh] max-h-full bg-medical-50 flex flex-col shadow-2xl chamfer-br pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      <header class="p-4 md:p-6 bg-surface border-b border-medical-200 flex items-center justify-between flex-shrink-0">
        <h2 class="text-xl md:text-2xl font-bold font-mono text-medical-900 flex items-center gap-2">
          <Settings class="w-5 h-5 text-accent" /> 设置
        </h2>
        <button @click="close" class="p-2 text-medical-500 hover:text-accent" aria-label="关闭设置"><X class="w-5 h-5" /></button>
      </header>

      <div class="flex-1 overflow-y-auto p-4 md:p-6 space-y-6">
        <section class="bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
          <h3 class="font-bold text-medical-900">我的设置</h3>
          <div>
            <p class="text-xs font-bold text-medical-500 mb-2">皮肤风格</p>
            <div class="flex flex-wrap gap-2">
              <button v-for="option in themes" :key="option.id" @click="changeTheme(option.id, $event)"
                      :aria-pressed="uiStore.theme === option.id"
                      class="px-4 py-2 border text-sm font-bold transition-colors"
                      :class="uiStore.theme === option.id ? 'border-accent text-accent bg-accent/10' : 'border-medical-200 text-medical-600 hover:border-accent'">
                <span class="inline-block w-3 h-3 mr-2 align-middle rounded-full" :style="{ backgroundColor: option.color }"></span>{{ option.label }}
              </button>
            </div>
          </div>

          <div class="border-t border-medical-200 pt-4">
            <p class="text-xs font-bold text-medical-500 mb-2">绑定网易云用户</p>
            <p v-if="userStore.bindings.netease" class="text-sm text-medical-800 mb-2">
              当前绑定：{{ userStore.neteaseUsername || userStore.bindings.netease }}
            </p>
            <div class="flex gap-2">
              <input v-model="userKeyword" @keyup.enter="searchUsers" placeholder="搜索网易云用户名"
                     class="flex-1 min-w-0 bg-medical-50 border border-medical-200 px-3 py-2 outline-none focus:border-accent text-medical-900" />
              <button @click="searchUsers" :disabled="searching" class="px-4 py-2 bg-accent hover:bg-accent-hover text-white disabled:opacity-50">搜索</button>
            </div>
            <div v-if="searchResults.length" class="mt-2 border border-medical-200 max-h-48 overflow-y-auto">
              <button v-for="result in searchResults" :key="result.id" @click="bindUser(result)"
                      class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-medical-100 text-medical-800">
                <img :src="result.avatarUrl" alt="" class="w-7 h-7 rounded-full" />
                <span class="truncate">{{ result.name }}</span>
              </button>
            </div>
          </div>
        </section>

        <section class="bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
          <div class="flex items-center gap-2">
            <h3 class="font-bold text-medical-900">房间管理设置</h3>
            <button @click="unlocked ? lock() : showUnlock = true" :title="unlocked ? '锁定设置' : '管理员解锁'"
                    :aria-label="unlocked ? '锁定房间设置' : '解锁房间设置'"
                    class="p-1.5 border border-medical-200 text-accent hover:bg-accent/10">
              <Unlock v-if="unlocked" class="w-4 h-4" /><Lock v-else class="w-4 h-4" />
            </button>
            <span class="text-xs text-medical-400">{{ unlocked ? '已解锁' : '仅管理员可修改' }}</span>
          </div>

          <fieldset :disabled="!unlocked" class="space-y-4 disabled:opacity-45">
            <div>
              <label class="block text-xs font-bold text-medical-500 mb-1">解析音质上限</label>
              <select v-model="draft.neteaseQuality" class="w-full bg-medical-50 border border-medical-200 p-2 text-medical-900">
                <option v-for="option in qualities" :key="option.value" :value="option.value">{{ option.label }}</option>
              </select>
            </div>
            <div v-for="group in fieldGroups" :key="group.title">
              <h4 class="text-xs font-bold text-medical-700 mb-2">{{ group.title }}</h4>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <label v-for="field in group.fields" :key="field.key" class="text-xs text-medical-500">
                  {{ field.label }}
                  <input v-model.number="draft[field.key]" type="number" :min="field.min" :max="field.max" step="1"
                         class="block w-full mt-1 bg-medical-50 border border-medical-200 p-2 outline-none focus:border-accent text-medical-900" />
                </label>
              </div>
            </div>
            <button @click.prevent="saveSettings" :disabled="saving" class="w-full bg-strong text-white py-2 font-bold hover:bg-accent disabled:opacity-50">
              {{ saving ? '正在保存...' : '保存房间设置' }}
            </button>
          </fieldset>
        </section>
      </div>
    </div>

    <div v-if="showUnlock" class="fixed inset-0 z-[80] bg-overlay/70 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-label="管理员验证">
      <div class="w-full max-w-sm bg-surface border border-medical-200 p-5 shadow-2xl space-y-4">
        <div class="flex justify-between items-center"><h3 class="font-bold text-medical-900">管理员验证</h3><button @click="showUnlock = false" aria-label="关闭"><X class="w-5 h-5 text-medical-500" /></button></div>
        <input v-model="passwordInput" type="password" @keyup.enter="unlock" placeholder="管理员密码"
               class="w-full bg-medical-50 border border-medical-200 p-2 outline-none focus:border-accent text-medical-900" />
        <button @click="unlock" :disabled="verifying" class="w-full bg-strong text-white py-2 font-bold hover:bg-accent disabled:opacity-50">验证并解锁</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue';
import { Settings, X, Lock, Unlock } from 'lucide-vue-next';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';
import { useToast } from '../composables/useToast';
import { musicApi } from '../api/music';
import { adminApi } from '../api/admin';

const props = defineProps({ isOpen: Boolean });
const emit = defineEmits(['close']);
const uiStore = useUiStore();
const userStore = useUserStore();
const playerStore = usePlayerStore();
const { success, error } = useToast();

const themes = [
  { id: 'classic', label: '白橙', color: '#F97316' },
  { id: 'night', label: '夜间橙', color: '#FB923C' },
  { id: 'blue', label: '浅蓝调', color: '#2563EB' }
];
const qualities = [
  { value: 'standard', label: '标准' },
  { value: 'higher', label: '较高' },
  { value: 'exhigh', label: '极高' },
  { value: 'lossless', label: '无损' },
  { value: 'hires', label: '高解析度' }
];
const fieldGroups = [
  { title: '播放队列控制', fields: [
    { key: 'maxQueueSize', label: '队列最大歌曲上限', min: 1, max: 10000 },
    { key: 'maxHistorySize', label: '历史记录歌曲上限', min: 0, max: 10000 },
    { key: 'maxUserSongs', label: '单人歌曲上限', min: 1, max: 10000 },
    { key: 'maxPlaylistImportSize', label: '歌单导入上限', min: 1, max: 10000 }
  ] },
  { title: '聊天室限制', fields: [
    { key: 'maxChatHistorySize', label: '消息历史条数', min: 0, max: 100000 },
    { key: 'minChatIntervalMs', label: '发言间隔（毫秒）', min: 0, max: 600000 },
    { key: 'maxChatMessageLength', label: '消息最大长度', min: 1, max: 10000 }
  ] }
];
const draft = ref({});
const unlocked = ref(false);
const showUnlock = ref(false);
const adminPassword = ref('');
const passwordInput = ref('');
const verifying = ref(false);
const saving = ref(false);
const userKeyword = ref('');
const searchResults = ref([]);
const searching = ref(false);

const lock = () => { unlocked.value = false; adminPassword.value = ''; };
const close = () => { lock(); showUnlock.value = false; emit('close'); };
watch(() => props.isOpen, (open) => {
  if (open) draft.value = { ...playerStore.config, neteaseQuality: playerStore.config.neteaseQuality || 'exhigh' };
  else { lock(); showUnlock.value = false; searchResults.value = []; }
});

const changeTheme = (name, event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  uiStore.setTheme(name, rect.left + rect.width / 2, rect.top + rect.height / 2);
};

const searchUsers = async () => {
  if (!userKeyword.value.trim() || searching.value) return;
  searching.value = true;
  searchResults.value = [];
  try { searchResults.value = await musicApi.searchUser('netease', userKeyword.value.trim()); }
  catch (e) { error('用户名搜索失败'); }
  finally { searching.value = false; }
};

const bindUser = (user) => {
  playerStore.bindAccount('netease', user.id, user.name);
  searchResults.value = [];
  userKeyword.value = '';
  success(`已绑定 ${user.name}`);
};

const unlock = async () => {
  if (!passwordInput.value || verifying.value) return;
  verifying.value = true;
  try {
    await adminApi.verify(passwordInput.value);
    adminPassword.value = passwordInput.value;
    unlocked.value = true;
    showUnlock.value = false;
    passwordInput.value = '';
  } catch (e) { error('管理员密码错误'); }
  finally { verifying.value = false; }
};

const saveSettings = async () => {
  if (!unlocked.value || saving.value) return;
  const update = { neteaseQuality: draft.value.neteaseQuality };
  for (const group of fieldGroups) {
    for (const field of group.fields) {
      const value = draft.value[field.key];
      if (!Number.isInteger(value) || value < field.min || value > field.max) {
        error(`${field.label}应在 ${field.min} 到 ${field.max} 之间`);
        return;
      }
      update[field.key] = value;
    }
  }
  saving.value = true;
  try {
    await adminApi.updateConfig(adminPassword.value, update);
    success('房间设置已生效并保存');
  } catch (e) { error(e.response?.data?.message || '房间设置保存失败'); }
  finally { saving.value = false; }
};
</script>
