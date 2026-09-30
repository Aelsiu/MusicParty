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
          <div class="border-t border-medical-200 pt-4 space-y-3">
            <div class="flex items-center gap-2">
              <p class="text-xs font-bold text-medical-500">自定义配色</p>
              <span v-if="uiStore.theme === 'custom'" class="text-[10px] font-mono text-accent">使用中</span>
            </div>
            <div class="flex flex-wrap md:flex-nowrap items-end gap-4">
              <div class="flex-shrink-0">
                <p class="text-xs text-medical-500 mb-2">白 / 暗基调</p>
                <div class="inline-flex border border-medical-200" role="group" aria-label="自定义主题基调">
                  <button v-for="base in [{ id: 'light', label: '白' }, { id: 'dark', label: '暗' }]" :key="base.id"
                          @click="customDraft.base = base.id" :aria-pressed="customDraft.base === base.id"
                          class="px-4 py-2 text-sm font-bold transition-colors"
                          :class="customDraft.base === base.id ? 'bg-accent text-white' : 'bg-medical-50 text-medical-600 hover:text-accent'">
                    {{ base.label }}
                  </button>
                </div>
              </div>
              <div class="flex-shrink-0">
                <label for="custom-theme-color" class="block text-xs text-medical-500 mb-2">主题色</label>
                <div class="flex items-center gap-2 h-9">
                  <input id="custom-theme-color" v-model="customDraft.color" type="color" aria-label="选择自定义主题色"
                         class="w-10 h-9 p-0.5 border border-medical-200 bg-surface cursor-pointer" />
                  <span class="text-xs font-mono text-medical-600">{{ customDraft.color.toUpperCase() }}</span>
                </div>
              </div>
              <div class="flex-shrink-0">
                <p class="text-xs text-medical-500 mb-2">配色预览</p>
                <div class="flex items-center gap-3 h-9">
                  <span v-for="item in previewItems" :key="item.variable" class="flex items-center gap-1 text-[10px] text-medical-600 whitespace-nowrap">
                    <span class="w-4 h-4 border" :style="{ backgroundColor: previewColor(item.variable), borderColor: previewColor('--medical-300') }"></span>{{ item.label }}
                  </span>
                </div>
              </div>
              <button @click="applyCustomTheme" class="flex-shrink-0 h-9 px-4 bg-strong text-white text-sm font-bold hover:bg-accent transition-colors">应用</button>
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
                <button @click="unbindUser(accountPlatform.id)" :aria-label="`解绑${accountPlatform.label}`" :title="`解绑${accountPlatform.label}`"
                        class="flex-shrink-0 w-[54px] h-[34px] flex items-center justify-center border border-medical-300 text-accent hover:border-accent">
                  <Unlink2 class="w-5 h-5" />
                </button>
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
          <div class="flex items-center justify-between gap-4">
            <div>
              <p class="text-xs font-bold text-medical-500">可视化</p>
              <p class="text-xs text-medical-400 mt-1">让丝带与环形频谱随音乐舞动</p>
            </div>
            <RoundSwitch v-model="uiStore.visualizationEnabled" label="可视化" />
          </div>
          <div>
            <p class="text-xs font-bold text-medical-500 mb-2">歌词提前显示</p>
            <div class="lyric-slider relative inline-grid grid-cols-3 w-60 h-10 border border-medical-200 bg-medical-50 cursor-pointer" role="group" aria-label="歌词提前显示行数" @click="selectLyricSegment">
              <span class="lyric-thumb absolute top-0 bottom-0 left-0 w-1/3 bg-accent/15 border border-accent pointer-events-none"
                    :style="{ transform: `translateX(${uiStore.lyricPreviewLines * 100}%)` }"></span>
              <button v-for="count in [0, 1, 2]" :key="count" @click.stop="uiStore.setLyricPreviewLines(count)"
                      :aria-pressed="uiStore.lyricPreviewLines === count"
                      class="relative z-10 text-sm font-mono transition-colors"
                      :class="uiStore.lyricPreviewLines === count ? 'text-accent font-bold' : 'text-medical-600 font-normal hover:text-accent'">
                {{ count }}Line
              </button>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, watch } from 'vue';
import { Settings, Unlink2, X } from 'lucide-vue-next';
import RoundSwitch from './RoundSwitch.vue';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { usePlayerStore } from '../stores/player';
import { useToast } from '../composables/useToast';
import { musicApi } from '../api/music';
import { deriveCustomPalette } from '../utils/customTheme';

const props = defineProps({ isOpen: Boolean });
const emit = defineEmits(['close']);
const uiStore = useUiStore();
const userStore = useUserStore();
const playerStore = usePlayerStore();
const { success, error } = useToast();

const themes = [
  { id: 'classic', label: '白橙', color: '#F97316' },
  { id: 'night', label: '暗橙', color: '#FB923C' },
  { id: 'blue', label: '白蓝', color: '#2563EB' },
  { id: 'night-blue', label: '暗蓝', color: '#2563EB' },
  { id: 'green', label: '白绿', color: '#3A7754' },
  { id: 'night-green', label: '暗绿', color: '#15803D' }
];
const customDraft = reactive({ ...uiStore.customThemeConfig });
const customPreview = computed(() => deriveCustomPalette(customDraft));
const previewColor = name => `rgb(${customPreview.value[name]})`;
const previewItems = [
  { label: '基调色', variable: '--medical-50' },
  { label: '主题色', variable: '--accent' },
  { label: '边框色', variable: '--medical-200' },
  { label: '背景色', variable: '--surface' }
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
    Object.assign(customDraft, uiStore.customThemeConfig);
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

const applyCustomTheme = (event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  uiStore.setTheme('custom', rect.left + rect.width / 2, rect.top + rect.height / 2, customDraft);
};

const selectLyricSegment = (event) => {
  const rect = event.currentTarget.getBoundingClientRect();
  const count = Math.max(0, Math.min(2, Math.floor((event.clientX - rect.left) / (rect.width / 3))));
  uiStore.setLyricPreviewLines(count);
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

<style scoped>
.lyric-slider, .lyric-slider button, .lyric-thumb { border-radius: 0; }
.lyric-thumb { transition: transform 340ms cubic-bezier(.22, 1, .36, 1), box-shadow 200ms ease; }
.lyric-slider:hover .lyric-thumb { box-shadow: 0 3px 10px rgb(var(--accent) / .24); }
@media (prefers-reduced-motion: reduce) {
  .lyric-thumb { transition: none; }
}
</style>
