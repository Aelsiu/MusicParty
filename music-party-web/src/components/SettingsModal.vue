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
          <h3 class="font-bold text-medical-900">主题</h3>
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
        </section>

        <section class="bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
          <h3 class="font-bold text-medical-900">绑定用户</h3>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div v-for="accountPlatform in accountPlatforms" :key="accountPlatform.id" class="min-w-0 space-y-2">
              <div class="text-xs font-bold text-medical-500">{{ accountPlatform.label }}</div>
              <div v-if="userStore.bindings[accountPlatform.id]" class="flex items-center gap-3 p-3 border border-medical-200 bg-medical-50">
                <img v-if="userStore[accountPlatform.avatarField]" :src="userStore[accountPlatform.avatarField]" :alt="`${accountPlatform.label}头像`" class="w-11 h-11 flex-shrink-0 rounded-full object-cover" />
                <div v-else class="w-11 h-11 flex-shrink-0 rounded-full bg-accent/15 text-accent flex items-center justify-center font-bold">{{ accountPlatform.initial }}</div>
                <div class="min-w-0 flex-1">
                  <div class="text-[10px] text-medical-500">当前绑定 · {{ accountPlatform.label }}</div>
                  <div class="font-bold text-medical-900 truncate">{{ userStore[accountPlatform.nameField] || userStore.bindings[accountPlatform.id] }}</div>
                </div>
                <button @click="unbindUser(accountPlatform.id)" class="flex-shrink-0 px-3 py-1.5 border border-medical-300 text-sm text-medical-600 hover:border-accent hover:text-accent">解绑</button>
              </div>
              <div class="flex gap-2">
                <input v-model="userKeyword[accountPlatform.id]" @keyup.enter="searchUsers(accountPlatform.id)" :placeholder="accountPlatform.placeholder" :aria-label="accountPlatform.placeholder"
                       class="flex-1 min-w-0 bg-medical-50 border border-medical-200 px-3 py-2 outline-none focus:border-accent text-medical-900" />
                <button @click="searchUsers(accountPlatform.id)" :disabled="searching[accountPlatform.id]" class="flex-shrink-0 px-3 py-2 bg-accent hover:bg-accent-hover text-white disabled:opacity-50">搜索</button>
              </div>
              <div v-if="searchResults[accountPlatform.id].length" class="border border-medical-200 max-h-48 overflow-y-auto">
                <button v-for="result in searchResults[accountPlatform.id]" :key="result.id" @click="bindUser(accountPlatform.id, result)"
                        class="w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-medical-100 text-medical-800">
                  <img :src="result.avatarUrl" alt="" class="w-7 h-7 rounded-full" />
                  <span class="truncate">{{ result.name }}</span>
                </button>
              </div>
              <div v-else-if="hasSearched[accountPlatform.id] && !searching[accountPlatform.id]" class="border border-medical-200 p-3 text-center text-xs text-medical-400">未查到用户</div>
            </div>
          </div>
        </section>

        <section class="bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
          <h3 class="font-bold text-medical-900">其他设置</h3>
          <div>
            <p class="text-xs font-bold text-medical-500 mb-2">歌词提前显示</p>
            <div class="inline-flex border border-medical-200" role="group" aria-label="歌词提前显示行数">
              <button v-for="count in [0, 1, 2]" :key="count" @click="uiStore.setLyricPreviewLines(count)"
                      :aria-pressed="uiStore.lyricPreviewLines === count"
                      class="px-4 py-2 text-sm font-mono font-bold transition-colors border-r last:border-r-0 border-medical-200"
                      :class="uiStore.lyricPreviewLines === count ? 'bg-accent text-white' : 'bg-medical-50 text-medical-600 hover:text-accent'">
                {{ count }}L
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue';
import { Settings, X } from 'lucide-vue-next';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';
import { useToast } from '../composables/useToast';
import { musicApi } from '../api/music';

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
const accountPlatforms = [
  { id: 'netease', label: '网易云音乐', placeholder: '搜索网易云用户名', initial: '云', nameField: 'neteaseUsername', avatarField: 'neteaseAvatar' },
  { id: 'bilibili', label: 'Bilibili', placeholder: '搜索Bilibili用户名', initial: 'B', nameField: 'bilibiliUsername', avatarField: 'bilibiliAvatar' }
];
const userKeyword = reactive({ netease: '', bilibili: '' });
const searchResults = reactive({ netease: [], bilibili: [] });
const searching = reactive({ netease: false, bilibili: false });
const hasSearched = reactive({ netease: false, bilibili: false });
for (const { id } of accountPlatforms) {
  watch(() => userKeyword[id], () => {
    hasSearched[id] = false;
    searchResults[id] = [];
  });
}

const close = () => emit('close');
watch(() => props.isOpen, (open) => {
  if (open) {
    hydrateBoundProfile();
  } else {
    for (const { id } of accountPlatforms) {
      searchResults[id] = [];
      hasSearched[id] = false;
    }
  }
});

const hydrateBoundProfile = async () => {
  for (const { id, nameField, avatarField } of accountPlatforms) {
    if (!userStore.bindings[id] || userStore[avatarField] || !userStore[nameField]) continue;
    try {
      const users = await musicApi.searchUser(id, userStore[nameField]);
      const match = users.find(user => String(user.id) === String(userStore.bindings[id]));
      if (match) userStore.updateBinding(id, match.id, match.name, match.avatarUrl);
    } catch (e) { /* 旧绑定保留，头像不可用时显示占位图 */ }
  }
};

const changeTheme = (name, event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  uiStore.setTheme(name, rect.left + rect.width / 2, rect.top + rect.height / 2);
};

const searchUsers = async (platform) => {
  if (!userKeyword[platform].trim() || searching[platform]) return;
  const keyword = userKeyword[platform].trim();
  searching[platform] = true;
  searchResults[platform] = [];
  hasSearched[platform] = false;
  try {
    const results = await musicApi.searchUser(platform, keyword);
    if (userKeyword[platform].trim() === keyword) {
      searchResults[platform] = results;
      hasSearched[platform] = true;
    }
  }
  catch (e) { error('用户名搜索失败'); }
  finally { searching[platform] = false; }
};

const bindUser = (platform, user) => {
  playerStore.bindAccount(platform, user.id, user.name, user.avatarUrl);
  searchResults[platform] = [];
  hasSearched[platform] = false;
  userKeyword[platform] = '';
  success(`已绑定 ${user.name}`);
};

const unbindUser = (platform) => {
  playerStore.bindAccount(platform, '');
  searchResults[platform] = [];
  hasSearched[platform] = false;
  userKeyword[platform] = '';
  success(`已解绑${platform === 'netease' ? '网易云' : 'Bilibili'}`);
};
</script>
