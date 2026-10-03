<template>
  <div class="pairing-code relative inline-flex flex-col min-w-0 shrink-0" :class="{ 'pairing-inline': inline, 'pairing-compact': compact }">
    <div class="flex items-center" :class="inline ? 'gap-1' : 'gap-2'">
      <span class="font-mono text-accent whitespace-nowrap" :class="inline ? 'text-[11px] tracking-[.12em] leading-none' : compact ? 'text-sm tracking-[.15em]' : 'text-xl sm:text-2xl tracking-[.15em]'">{{ code || '----' }}</span>
      <button type="button" @click="copy" :disabled="!canCopy" class="pairing-copy relative shrink-0 text-medical-400 disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
              :style="{ '--progress': `${progress}%` }" :aria-label="copyLabel">
        <span class="pairing-copy-center">
          <Check v-if="copied" class="text-accent" :class="inline ? 'w-3 h-3' : 'w-4 h-4'" aria-hidden="true" />
          <Copy v-else :class="inline ? 'w-3 h-3' : 'w-4 h-4'" aria-hidden="true" />
        </span>
        <span v-if="copied" role="status" class="sr-only">已复制</span>
      </button>
    </div>
    <p v-if="error" role="alert" class="text-xs text-red-500" :class="inline ? 'pairing-inline-error' : 'mt-2'">{{ error }}</p>
  </div>
</template>
<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue';
import { Check, Copy } from 'lucide-vue-next';
import { copyAsyncText } from '../utils/clipboard';
import { pairingRemaining, pairingProgress } from '../utils/pairingClock';
import { isPairingCode } from '../utils/pairingCode';
const props=defineProps({code:String,compact:Boolean,inline:Boolean,nextUpdateAt:Number,serverTime:Number,
  intervalMinutes:{type:Number,default:10},clockOffset:Number,copyLabel:{type:String,default:'复制配对码'}});
const now=ref(Date.now()),offset=ref(0),copied=ref(false),error=ref('');
let copyTimer,clockTimer,copyGeneration=0,alive=true;
watch(()=>props.serverTime,value=>{offset.value=Number.isFinite(value)?value-Date.now():0;now.value=Date.now();},{immediate:true});
watch(()=>props.code,()=>{++copyGeneration;clearTimeout(copyTimer);copied.value=false;error.value='';});
const remaining=computed(()=>pairingRemaining(props.nextUpdateAt,now.value,props.clockOffset??offset.value));
const progress=computed(()=>pairingProgress(props.nextUpdateAt,props.intervalMinutes,now.value,props.clockOffset??offset.value));
const canCopy=computed(()=>isPairingCode(props.code)&&(!props.nextUpdateAt||remaining.value>0));
async function copy(){
  if(!canCopy.value)return;
  const current=++copyGeneration;
  try{
    await copyAsyncText(props.code);
    if(!alive||current!==copyGeneration)return;
    error.value='';clearTimeout(copyTimer);copied.value=true;
    copyTimer=setTimeout(()=>{copied.value=false;},1000);
  }catch{if(alive&&current===copyGeneration){clearTimeout(copyTimer);copied.value=false;error.value='无法访问剪贴板，请手动复制配对码';}}
}
onMounted(()=>{clockTimer=setInterval(()=>{now.value=Date.now();},1000);});
onBeforeUnmount(()=>{alive=false;++copyGeneration;clearTimeout(copyTimer);clearInterval(clockTimer);});
</script>
<style scoped>
.pairing-copy{width:40px;height:40px;padding:2px;border-radius:50%;background:conic-gradient(rgb(var(--accent)) var(--progress),rgb(var(--medical-200)) 0)}
.pairing-copy-center{width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:50%;background:rgb(var(--surface))}
.pairing-compact .pairing-copy{width:28px;height:28px}
.pairing-inline .pairing-copy{width:24px;height:24px}
.pairing-inline-error{position:absolute;top:100%;right:0;z-index:60;width:12rem;max-width:80vw;padding:.5rem;background:rgb(var(--surface));border:1px solid rgb(var(--medical-200))}
@media(max-width:400px){.pairing-code:not(.pairing-inline):not(.pairing-compact) .pairing-copy{width:32px;height:32px}}
@media(pointer:coarse){.pairing-copy:before{content:'';position:absolute;width:44px;height:44px;left:50%;top:50%;transform:translate(-50%,-50%)}}
</style>
