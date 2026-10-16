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
        <div class="grid grid-cols-1 md:grid-cols-[minmax(0,1fr)_276px] gap-4 items-stretch">
          <section class="min-w-0 bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
            <h3 class="font-bold text-medical-900">主题</h3>
            <div>
              <p class="text-xs font-bold text-medical-500 mb-2">皮肤风格</p>
              <div class="grid grid-cols-3 gap-2" role="group" aria-label="皮肤风格">
                <button v-for="option in themes" :key="option.id" @click="changeTheme(option.id, $event)"
                        :aria-pressed="uiStore.theme === option.id"
                        class="px-2 sm:px-3 py-2 border text-sm font-bold whitespace-nowrap transition-colors"
                        :class="uiStore.theme === option.id ? 'border-accent text-accent bg-accent/10' : 'border-medical-200 text-medical-600 hover:border-accent'">
                  <span class="inline-block w-3 h-3 mr-2 align-middle rounded-full" :style="{ backgroundColor: option.color }"></span>{{ option.label }}
                </button>
              </div>
            </div>
            <div class="custom-theme-section border-t border-medical-200 pt-4 space-y-3">
              <div class="flex items-center gap-2">
                <p class="text-xs font-bold text-medical-500">自定义配色</p>
                <span v-if="uiStore.theme === 'custom'" class="text-[10px] font-mono text-accent">使用中</span>
              </div>
              <div class="custom-theme-controls">
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
                    <span class="custom-theme-hex text-xs font-mono text-medical-600">{{ customDraft.color.toUpperCase() }}</span>
                  </div>
                </div>
                <div class="palette-preview">
                  <p class="text-xs text-medical-500 mb-2">配色预览</p>
                  <div class="palette-preview-swatches flex items-center gap-2 h-9">
                    <span v-for="item in previewItems" :key="item.variable" class="palette-preview-item flex items-center gap-1 text-[10px] text-medical-600 whitespace-nowrap"
                          :class="{ 'palette-preview-background': item.variable === '--surface' }">
                      <span class="w-4 h-4 shrink-0 border" :style="{ backgroundColor: previewColor(item.variable), borderColor: previewColor('--medical-300') }"></span>{{ item.label }}
                    </span>
                  </div>
                </div>
                <button @click="applyCustomTheme" class="flex-shrink-0 h-9 px-4 bg-strong text-white text-sm font-bold hover:bg-accent transition-colors">应用</button>
              </div>
            </div>
          </section>

          <section class="min-w-0 bg-surface border border-medical-200 p-4 md:p-5 space-y-4">
            <h3 class="font-bold text-medical-900">其他设置</h3>
            <div class="space-y-5">
              <div class="space-y-2">
                <div class="flex items-center justify-between gap-4 h-7">
                  <p class="text-xs font-bold text-medical-500">可视化</p>
                  <RoundSwitch v-model="uiStore.visualizationEnabled" label="可视化" />
                </div>
                <p class="info-tip text-xs text-medical-400">让丝带与环形频谱随音乐舞动</p>
              </div>
              <div class="space-y-2">
                <p class="text-xs font-bold text-medical-500 h-7 flex items-center">歌词提前显示</p>
                <div class="lyric-slider relative inline-grid grid-cols-3 w-36 h-7 shrink-0 border border-medical-200 bg-medical-50 cursor-pointer" role="group" aria-label="歌词提前显示行数" @click="selectLyricSegment">
                  <span class="lyric-thumb absolute top-0 bottom-0 left-0 w-1/3 bg-accent/15 border border-accent pointer-events-none"
                        :style="{ transform: `translateX(${uiStore.lyricPreviewLines * 100}%)` }"></span>
                  <button v-for="count in [0, 1, 2]" :key="count" @click.stop="uiStore.setLyricPreviewLines(count)"
                          :aria-pressed="uiStore.lyricPreviewLines === count"
                          class="relative z-10 text-[11px] font-mono transition-colors"
                          :class="uiStore.lyricPreviewLines === count ? 'text-accent font-bold' : 'text-medical-600 font-normal hover:text-accent'">
                    {{ count }}Line
                  </button>
                </div>
              </div>
              <section class="danmaku-settings border-t border-medical-200 pt-4" aria-label="弹幕设置">
                <h4 class="text-xs font-bold text-medical-900 mb-2">弹幕设置</h4>
                <div v-for="setting in danmakuControls" :key="setting.key" class="danmaku-setting-row">
                  <label :for="`danmaku-${setting.key}`" class="text-xs text-medical-500">{{ setting.label }}</label>
                  <input :id="`danmaku-${setting.key}`" :data-danmaku-setting="setting.key" type="range" :min="setting.min" :max="setting.max" :step="setting.step"
                         :value="setting.key === 'speed' ? DANMAKU_SPEEDS.indexOf(uiStore.danmakuSettings.speed) : uiStore.danmakuSettings[setting.key]"
                         :aria-valuetext="danmakuSettingLabel(setting.key)" :style="danmakuSliderStyle(setting)" @input="changeDanmakuSetting(setting.key, $event)" />
                  <output :for="`danmaku-${setting.key}`" class="text-xs text-medical-600 tabular-nums">{{ danmakuSettingLabel(setting.key) }}</output>
                </div>
              </section>
            </div>
          </section>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, watch } from 'vue';
import { Settings, X } from 'lucide-vue-next';
import RoundSwitch from './RoundSwitch.vue';
import { useUiStore } from '../stores/ui';
import { deriveCustomPalette } from '../utils/customTheme';
import { DANMAKU_SPEEDS, DANMAKU_SPEED_LABELS } from '../utils/danmakuSettings';

const props = defineProps({ isOpen: Boolean });
const emit = defineEmits(['close']);
const uiStore = useUiStore();
const danmakuControls = [
  { key: 'area', label: '显示区域', min: 25, max: 100, step: 25 },
  { key: 'opacity', label: '不透明度', min: 0, max: 100, step: 5 },
  { key: 'fontSize', label: '弹幕字号', min: 50, max: 150, step: 5 },
  { key: 'speed', label: '弹幕速度', min: 0, max: 4, step: 1 }
];
const danmakuSettingLabel = key => key === 'speed' ? DANMAKU_SPEED_LABELS[DANMAKU_SPEEDS.indexOf(uiStore.danmakuSettings.speed)] : `${uiStore.danmakuSettings[key]}%`;
const changeDanmakuSetting = (key, event) => uiStore.setDanmakuSetting(key, key === 'speed' ? DANMAKU_SPEEDS[Number(event.target.value)] : Number(event.target.value));
const danmakuSliderStyle = setting => {
  const value = setting.key === 'speed' ? DANMAKU_SPEEDS.indexOf(uiStore.danmakuSettings.speed) : uiStore.danmakuSettings[setting.key];
  return { '--danmaku-fill': `${(value - setting.min) / (setting.max - setting.min) * 100}%` };
};

const themes = [
  { id: 'classic', label: '白橙', color: '#F97316' },
  { id: 'blue', label: '白蓝', color: '#2563EB' },
  { id: 'green', label: '白绿', color: '#3A7754' },
  { id: 'night', label: '暗橙', color: '#FB923C' },
  { id: 'night-blue', label: '暗蓝', color: '#2563EB' },
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
const close = () => emit('close');
watch(() => props.isOpen, (open) => {
  if (open) {
    Object.assign(customDraft, uiStore.customThemeConfig);
  }
});

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

</script>

<style scoped>
.danmaku-setting-row { display: grid; grid-template-columns: 60px minmax(0, 1fr) 34px; align-items: center; gap: 8px; min-height: 36px; }
.danmaku-setting-row output { text-align: right; }
.danmaku-setting-row input { appearance: none; width: 100%; min-width: 0; height: 28px; cursor: pointer; background: transparent; }
.danmaku-setting-row input::-webkit-slider-runnable-track { height: 5px; border-radius: 3px; background: linear-gradient(to right, rgb(var(--accent)) var(--danmaku-fill), rgb(var(--medical-200)) var(--danmaku-fill)); }
.danmaku-setting-row input::-moz-range-track { height: 5px; border-radius: 3px; background: rgb(var(--medical-200)); }
.danmaku-setting-row input::-moz-range-progress { height: 5px; border-radius: 3px; background: rgb(var(--accent)); }
.danmaku-setting-row input::-webkit-slider-thumb { appearance: none; width: 14px; height: 14px; margin-top: -4.5px; border-radius: 50%; border: 2px solid rgb(var(--accent)); background: rgb(var(--surface)); }
.danmaku-setting-row input::-moz-range-thumb { width: 10px; height: 10px; border-radius: 50%; border: 2px solid rgb(var(--accent)); background: rgb(var(--surface)); }
.danmaku-setting-row input:focus-visible { outline: 2px solid rgb(var(--accent)); outline-offset: 2px; }
@media (pointer: coarse) { .danmaku-setting-row { min-height: 44px; } .danmaku-setting-row input { height: 40px; } }
.custom-theme-section { container: custom-theme / inline-size; }
.custom-theme-controls { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; }
.palette-preview { flex-shrink: 0; }
@container custom-theme (max-width: 512px) {
  .palette-preview-background { display: none; }
}
@container custom-theme (min-width: 324px) {
  .custom-theme-controls { display: grid; grid-template-columns: max-content max-content minmax(0, 1fr) max-content; }
  .palette-preview { min-width: 0; }
}
@container custom-theme (min-width: 324px) and (max-width: 479px) {
  .custom-theme-controls { gap: 8px; }
  .custom-theme-hex { display: none; }
}
@container custom-theme (min-width: 324px) and (max-width: 383px) {
  .palette-preview-item { flex-direction: column; gap: 2px; }
  .palette-preview-swatches { justify-content: space-between; gap: 4px; }
}
.lyric-slider, .lyric-slider button, .lyric-thumb { border-radius: 0; }
.lyric-thumb { transition: transform 340ms cubic-bezier(.22, 1, .36, 1), box-shadow 200ms ease; }
.lyric-slider:hover .lyric-thumb { box-shadow: 0 3px 10px rgb(var(--accent) / .24); }
@media (prefers-reduced-motion: reduce) {
  .lyric-thumb { transition: none; }
}
</style>
