<template>
  <div>
    <button @click="open=true;start()" :disabled="busy" class="w-full bg-accent text-white py-3 font-bold text-sm flex items-center justify-center gap-2"><QrCode class="w-4 h-4" /> 获取 Cookie</button>
    <Teleport to="body">
    <div v-if="open" role="dialog" aria-modal="true" aria-label="网易云音乐扫码登录" class="fixed inset-0 z-[140] bg-overlay/60 backdrop-blur-sm flex items-center justify-center p-4 text-medical-900 font-sans">
      <section class="bg-surface border border-medical-200 chamfer-br w-full max-w-sm">
        <header class="p-4 border-b border-medical-200 bg-medical-50 flex justify-between"><h3 class="font-mono text-sm font-bold">NETEASE LOGIN</h3><button @click="close" aria-label="关闭二维码登录"><X class="w-4 h-4" /></button></header>
        <div class="p-6 text-center">
          <img v-if="image && !['EXPIRED','FAILED','SUCCESS'].includes(state)" :src="image" alt="网易云音乐登录二维码" class="w-52 h-52 mx-auto bg-white">
          <div v-else class="h-32 flex items-center justify-center"><Loader2 v-if="busy" class="w-8 h-8 animate-spin" /><CheckCircle2 v-else-if="state==='SUCCESS'" class="w-12 h-12 text-accent" /></div>
          <p class="font-bold text-sm mt-4" aria-live="polite">{{ labels[state]||'正在创建二维码' }}</p>
          <p v-if="error" role="alert" class="text-xs text-red-500 mt-3">{{ error }}</p>
          <div class="relative mt-3 text-[11px] text-medical-400">
            <p class="flex items-center justify-center gap-1">
              <span>使用网易云音乐App扫码并确认登录</span>
              <button type="button" class="shrink-0 p-1 hover:text-accent focus-visible:text-accent"
                      aria-label="Cookie 登录用途说明" :aria-describedby="helpVisible ? helpId : undefined" :aria-expanded="helpVisible"
                      @pointerenter="helpHovered=$event.pointerType==='mouse'" @pointerleave="helpHovered=false"
                      @focus="helpFocused=$event.target.matches(':focus-visible')" @blur="helpFocused=false;helpPinned=false"
                      @click="helpPinned=!helpPinned" @keydown.esc.stop="hideHelp">
                <CircleHelp class="w-3.5 h-3.5" />
              </button>
            </p>
            <div v-if="helpVisible" :id="helpId" role="tooltip"
                 class="absolute bottom-full left-0 right-0 mb-2 p-3 bg-strong text-white border border-medical-300 shadow-lg text-left text-xs leading-relaxed z-10">
              本应用登录仅用于获取并保存Cookie，不会用作其他用途。如遇账号问题，可能是真的被攻击了，请立刻修改密码并加强自身账号保护！
            </div>
          </div>
          <button v-if="state==='EXPIRED'||state==='FAILED'" @click="start" :disabled="busy" class="w-full bg-accent text-white py-3 mt-5 font-bold text-sm">RETRY</button>
          <button @click="close" class="w-full border border-medical-200 py-3 mt-3 text-sm font-bold">{{ state==='SUCCESS'?'DONE':'CANCEL' }}</button>
        </div>
      </section>
    </div>
    </Teleport>
  </div>
</template>
<script setup>
import { ref, computed, useId, onBeforeUnmount } from 'vue';
import { QrCode, X, Loader2, CheckCircle2, CircleHelp } from 'lucide-vue-next';
import { roomsApi } from '../api/rooms';
import { registerBackHandler } from '../services/backNavigation';
const open=ref(false),image=ref(''),state=ref(''),error=ref(''),busy=ref(false);
const helpId=useId(),helpHovered=ref(false),helpFocused=ref(false),helpPinned=ref(false);
const helpVisible=computed(()=>helpHovered.value||helpFocused.value||helpPinned.value);
function hideHelp(){helpHovered.value=false;helpFocused.value=false;helpPinned.value=false;}
const labels={WAITING:'等待扫码',CONFIRMING:'已扫码，请在手机上确认',VALIDATING:'正在验证并保存凭据',SUCCESS:'Cookie 已保存',EXPIRED:'二维码已过期',FAILED:'登录失败，请重试'};
let task,timer,generation=0;
async function start(){clearTimeout(timer);const current=++generation;busy.value=true;error.value='';state.value='';image.value='';try{const result=await roomsApi.qrCreate();if(current!==generation||!open.value){roomsApi.qrCancel(result.task).catch(()=>{});return;}task=result.task;image.value=result.image;state.value=result.state;timer=setTimeout(()=>poll(current),2000);}catch(e){if(current===generation){state.value='FAILED';error.value=e.response?.data?.message||'二维码创建失败';}}finally{if(current===generation)busy.value=false;}}
async function poll(current){if(!open.value||current!==generation)return;try{const result=await roomsApi.qrCheck(task);if(current!==generation)return;state.value=result.state;if(!['SUCCESS','EXPIRED','FAILED'].includes(state.value))timer=setTimeout(()=>poll(current),2000);}catch(e){if(current===generation){state.value='FAILED';error.value=e.response?.data?.message||'登录状态查询失败';}}}
function close(){open.value=false;hideHelp();generation++;busy.value=false;clearTimeout(timer);if(task)roomsApi.qrCancel(task).catch(()=>{});task=null;image.value='';}
const unregisterBack=registerBackHandler(140,()=>{if(!open.value)return false;close();return true;});
onBeforeUnmount(()=>{close();unregisterBack();});
</script>
