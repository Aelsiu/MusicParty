<template>
  <div class="flex items-center gap-2 volume-control" :class="{ 'w-full': !compact }">
    <button @click="toggleMute" class="text-medical-500 hover:text-accent shrink-0" :aria-label="ui.volume ? '静音' : '取消静音'">
      <VolumeX v-if="ui.volume === 0" class="w-5 h-5" /><Volume1 v-else-if="ui.volume < 0.5" class="w-5 h-5" /><Volume2 v-else class="w-5 h-5" />
    </button>
    <div class="min-w-0" :class="compact ? 'w-[7.2rem]' : 'flex-1'">
      <input type="range" min="0" max="1" step="0.01" :value="ui.volume" @input="ui.setVolume(Number($event.target.value))" aria-label="音量 0% 到 100%" class="volume-range w-full block" :style="{ '--fill': `${ui.volume * 100}%` }" />
    </div>
    <div class="w-12 shrink-0 text-[10px] font-mono text-medical-400 text-right">
      <span v-if="editing" class="flex items-center justify-end">
        <input ref="input" v-model="draft" type="text" inputmode="numeric" maxlength="3" aria-label="音量百分比" @keydown.enter="commit" @keydown.esc="editing=false" @blur="editing=false" class="w-8 min-w-0 border-b border-accent bg-transparent text-right text-medical-900 outline-none" />%
      </span>
      <button v-else @click="edit" aria-label="输入音量百分比" title="点击输入音量，范围 0%–100%" class="hover:text-accent">{{ Math.round(ui.volume * 100) }}%</button>
    </div>
  </div>
</template>
<script setup>
import { ref, nextTick } from 'vue';
import { VolumeX, Volume1, Volume2 } from 'lucide-vue-next';
import { useUiStore } from '../stores/ui';
import { DEFAULT_VOLUME } from '../utils/volumeMapping';
defineProps({ compact: Boolean });
const ui=useUiStore(),editing=ref(false),draft=ref(''),input=ref(null),lastVolume=ref(DEFAULT_VOLUME);
async function edit(){draft.value=String(Math.round(ui.volume*100));editing.value=true;await nextTick();input.value?.select();}
function commit(){if(/^\d{1,3}$/.test(draft.value.trim()))ui.setVolume(Math.min(100,Number(draft.value))/100);editing.value=false;}
function toggleMute(){if(ui.volume>0){lastVolume.value=ui.volume;ui.setVolume(0);}else ui.setVolume(lastVolume.value);}
</script>
<style scoped>
.volume-range{appearance:none;height:16px;margin:0;background:transparent;cursor:pointer;touch-action:pan-y}
.volume-range::-webkit-slider-runnable-track{height:4px;background:linear-gradient(to right,rgb(var(--accent)) var(--fill),rgb(var(--medical-200)) var(--fill))}
.volume-range::-moz-range-track{height:4px;background:rgb(var(--medical-200))}
.volume-range::-moz-range-progress{height:4px;background:rgb(var(--accent))}
.volume-range::-webkit-slider-thumb{appearance:none;width:6px;height:12px;margin-top:-4px;background:rgb(var(--strong));border:1px solid rgb(var(--surface));border-radius:0}
.volume-range::-moz-range-thumb{width:6px;height:12px;background:rgb(var(--strong));border:1px solid rgb(var(--surface));border-radius:0}
.volume-range:focus-visible{outline:1px solid rgb(var(--accent));outline-offset:2px}
</style>
