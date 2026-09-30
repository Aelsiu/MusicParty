<template>
  <div class="inline-flex flex-col min-w-0 shrink-0">
    <div class="flex items-center gap-2">
      <span class="font-mono text-accent whitespace-nowrap" :class="compact ? 'text-sm tracking-[.15em]' : 'text-2xl sm:text-3xl tracking-[.25em]'">{{ code || '----' }}</span>
      <button @click="copy" :disabled="!code" class="relative p-2 text-medical-400 disabled:opacity-40" :aria-label="copyLabel">
        <Copy :key="copySequence" class="w-4 h-4" :class="{ 'copy-pulse': copied }" />
        <span v-if="copied" :key="copySequence" role="status" class="copy-feedback absolute bottom-full left-1/2 whitespace-nowrap text-[11px] text-accent pointer-events-none">已复制</span>
      </button>
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500 mt-2">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, onBeforeUnmount } from 'vue';
import { Copy } from 'lucide-vue-next';
const props=defineProps({code:String,compact:Boolean,copyLabel:{type:String,default:'复制配对码'}});
const copied=ref(false),copySequence=ref(0),error=ref('');let copyTimer,copyGeneration=0,alive=true;
async function copy(){
  if(!props.code)return;
  const current=++copyGeneration;
  try{
    await navigator.clipboard.writeText(props.code);
    if(!alive||current!==copyGeneration)return;
    error.value='';clearTimeout(copyTimer);copied.value=true;copySequence.value++;
    copyTimer=setTimeout(()=>{copied.value=false;},500);
  }catch{if(alive&&current===copyGeneration){clearTimeout(copyTimer);copied.value=false;error.value='无法访问剪贴板，请手动复制配对码';}}
}
onBeforeUnmount(()=>{alive=false;clearTimeout(copyTimer);});
</script>
<style scoped>
.copy-pulse{animation:copy-pulse .5s ease-in-out}
.copy-feedback{animation:copy-float .5s ease-out both}
@keyframes copy-pulse{0%,100%{color:inherit}30%,55%{color:rgb(var(--accent))}}
@keyframes copy-float{0%{opacity:0;transform:translate(-50%,0)}15%{opacity:1}100%{opacity:0;transform:translate(-50%,-18px)}}
@media(prefers-reduced-motion:reduce){@keyframes copy-float{0%,100%{opacity:0;transform:translateX(-50%)}15%,75%{opacity:1;transform:translateX(-50%)}}}
</style>
