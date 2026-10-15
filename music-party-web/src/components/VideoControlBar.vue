<script setup>
import { shortVideoQuality } from '../utils/videoQualityLabel.js';
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
import { MonitorPlay, RefreshCw, PictureInPicture2, Expand, Maximize, Minimize, Loader2, ChevronDown } from 'lucide-vue-next';
const props=defineProps({recovering:Boolean,embeddedEligible:Boolean,embeddedActive:Boolean,canSwitchPlayer:Boolean,danmakuCapable:{type:Boolean,default:true},danmakuEnabled:Boolean,rate:Number,mobile:Boolean,pipSupported:Boolean,pipActive:Boolean,pageMode:String,requestPending:Boolean,menu:String,canManage:Boolean,canSwitchPage:Boolean,pages:{type:Array,default:()=>[]},currentPage:Number,roomStatus:String,retryRemaining:Number,canRefresh:Boolean,danmakuRetryable:Boolean,danmakuRetryEnabled:Boolean,danmakuRetryRemaining:Number,
  qualityLabel:{type:String,default:''},qualityOptions:{type:Array,default:()=>[]},qualityPreference:{type:Object,default:()=>({mode:'auto'})},qualityEnabled:Boolean});
const emit=defineEmits(['player','danmaku','menu','rate','quality','pip','web','fullscreen','refresh','danmaku-retry','height']);
const rates=[0.5,0.75,1,1.25,1.5,1.75,2];
const controlBar=ref(null),menuLeft=ref(null);
let resizeObserver;
function positionMenu(){
  const bar=controlBar.value,id=`video-${props.menu}-menu`;
  const height=bar?.getBoundingClientRect?.().height;if(Number.isFinite(height)&&height>0)emit('height',height);
  const button=bar?.querySelector(`[aria-controls="${id}"]`),menu=bar?.querySelector(`#${id}`);
  if(!button?.getBoundingClientRect||!menu?.getBoundingClientRect||!bar?.getBoundingClientRect)return;
  const bounds=bar.getBoundingClientRect(),trigger=button.getBoundingClientRect(),half=menu.getBoundingClientRect().width/2;
  const center=trigger.left+trigger.width/2-bounds.left;
  menuLeft.value=`${Math.max(12+half,Math.min(center,bounds.width-12-half))}px`;
}
watch(()=>props.menu,()=>{menuLeft.value=null;nextTick(positionMenu);},{flush:'post'});
onMounted(()=>{if(globalThis.ResizeObserver){resizeObserver=new ResizeObserver(positionMenu);resizeObserver.observe(controlBar.value);}nextTick(positionMenu);});
onBeforeUnmount(()=>resizeObserver?.disconnect());
</script>
<template>
  <div ref="controlBar" class="video-control-bar" aria-label="视频操作" data-video-interaction>
    <button v-if="embeddedEligible" type="button" :disabled="!canSwitchPlayer" :aria-pressed="embeddedActive" :aria-label="embeddedActive?'切换到网页播放器':'切换到扩展播放器'" :title="embeddedActive?'切换到网页播放器':'切换到扩展播放器'" :class="{selected:embeddedActive}" @click="emit('player')"><MonitorPlay class="w-4 h-4" aria-hidden="true" /></button>
    <button data-guide="video-source" type="button" :disabled="!canRefresh" aria-label="刷新视频源" :title="retryRemaining?`${retryRemaining} 秒后可刷新`:'刷新视频源'" @click="emit('refresh')"><Loader2 v-if="recovering" class="w-3 h-3 animate-spin" role="status" aria-label="正在同步本端"/><RefreshCw v-else class="w-4 h-4" aria-hidden="true" /></button>
    <span class="bar-spacer"></span>
    <div class="right-controls">
      <button v-if="danmakuCapable" data-guide="video-danmaku" type="button" :aria-pressed="danmakuEnabled" :aria-label="danmakuEnabled?'关闭弹幕':'开启弹幕'" :title="danmakuEnabled?'关闭弹幕':'开启弹幕'" :class="{selected:danmakuEnabled}" @click="emit('danmaku')"><span class="danmaku-icon" aria-hidden="true">弹</span></button>
      <div v-if="danmakuCapable && danmakuEnabled && danmakuRetryable" class="menu-anchor danmaku-retry-anchor">
        <button type="button" aria-label="弹幕操作" :aria-expanded="menu==='danmaku'" aria-controls="video-danmaku-menu" @click="emit('menu',menu==='danmaku'?'':'danmaku')"><ChevronDown class="w-3 h-3" /></button>
        <section v-if="menu==='danmaku'" id="video-danmaku-menu" class="control-menu" :style="{left:menuLeft}"><button type="button" :disabled="!danmakuRetryEnabled" @click="emit('danmaku-retry')" class="rate-choice">{{danmakuRetryRemaining?`${danmakuRetryRemaining} 秒后可重试`:'重试弹幕'}}</button></section>
      </div>
      <button v-if="!mobile" data-guide="video-view" type="button" :disabled="!pipSupported||requestPending" :aria-pressed="pipActive" :aria-label="pipActive?'退出画中画':'进入画中画'" :title="pipSupported?'原生画中画':'当前播放器不支持画中画'" @click="emit('pip')"><PictureInPicture2 class="w-4 h-4" aria-hidden="true"/></button>
      <div class="menu-anchor">
        <button data-guide="video-preferences" type="button" aria-label="房间倍速" :title="`当前倍速 X${rate}`" :aria-expanded="menu==='speed'" aria-controls="video-speed-menu" @click="emit('menu',menu==='speed'?'':'speed')" class="text-button">倍速</button>
        <section v-if="menu==='speed'" id="video-speed-menu" class="control-menu speed-menu" :style="{left:menuLeft}">
          <p v-if="!canManage" class="px-3 py-2 text-xs">仅管理者可修改</p><button type="button" v-for="value in rates" :key="value" :disabled="!canManage" :aria-pressed="rate===value" class="rate-choice font-mono" :class="{'chosen':rate===value}" @click="emit('rate',value)">X{{value}}<span v-if="rate===value" aria-hidden="true">✓</span></button>
        </section>
      </div>
      <div class="menu-anchor">
        <button type="button" class="text-button quality-button" aria-label="视频画质" :aria-description="qualityLabel || '暂无视频'" :title="`当前画质：${qualityLabel || '暂无视频'}`" :disabled="!qualityEnabled" :aria-expanded="menu==='quality'" aria-controls="video-quality-menu" @click="emit('menu',menu==='quality'?'':'quality')">{{shortVideoQuality(qualityLabel)}}</button>
        <section v-if="menu==='quality'" id="video-quality-menu" class="control-menu quality-menu" :style="{left:menuLeft}" aria-label="视频画质选项">
          <button type="button" class="rate-choice" :aria-pressed="qualityPreference.mode!=='manual'" :class="{chosen:qualityPreference.mode!=='manual'}" @click="emit('quality','auto')">自动（最高）<span v-if="qualityPreference.mode!=='manual'" aria-hidden="true">✓</span></button>
          <button type="button" v-for="quality in qualityOptions" :key="quality.id" class="rate-choice" :aria-pressed="qualityPreference.mode==='manual'&&qualityPreference.qualityId===quality.id" :class="{chosen:qualityPreference.mode==='manual'&&qualityPreference.qualityId===quality.id}" @click="emit('quality',quality.id)">{{quality.label}}<span v-if="qualityPreference.mode==='manual'&&qualityPreference.qualityId===quality.id" aria-hidden="true">✓</span></button>
        </section>
      </div>
      <button data-guide="video-view" type="button" :disabled="requestPending" :aria-pressed="pageMode==='WEB_FULLSCREEN'" :aria-label="pageMode==='WEB_FULLSCREEN'?'退出网页全屏':'进入网页全屏'" @click="emit('web')"><Minimize v-if="pageMode==='WEB_FULLSCREEN'" class="w-4 h-4"/><Expand v-else class="w-4 h-4"/></button>
      <button data-guide="video-view" type="button" :disabled="requestPending" :aria-pressed="pageMode==='CONTAINER_FULLSCREEN'" :aria-label="pageMode==='CONTAINER_FULLSCREEN'?'退出全屏':'进入全屏'" @click="emit('fullscreen')"><Minimize v-if="pageMode==='CONTAINER_FULLSCREEN'" class="w-4 h-4"/><Maximize v-else class="w-4 h-4"/></button>
    </div>
  </div>
</template>
<style scoped>
.video-control-bar{position:relative;display:flex;align-items:center;padding:.4px 6px;height:var(--video-control-height,44.8px);min-height:44.8px;box-sizing:border-box;color:#fff;background:rgba(0,0,0,.5)}button{display:flex;align-items:center;justify-content:center;min-width:44px;min-height:44px;border:1px solid transparent;color:inherit}button:hover{background:rgba(255,255,255,.12)}button:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:-2px}button:disabled{opacity:.4}.selected{color:rgb(var(--accent))}.bar-spacer{flex:1}.right-controls{display:flex;gap:4px;align-items:center}.danmaku-icon{font-size:11.2px;border:1.2px solid currentColor;border-radius:4px;line-height:14.4px;padding:0 2.4px}.menu-anchor{position:static}.control-menu{position:absolute;left:50%;transform:translateX(-50%);bottom:calc(var(--video-control-height,44.8px) + 4px);width:min(228px,calc(100% - 24px));max-height:var(--video-menu-max-height,min(330px,65dvh));overflow:auto;z-index:10;color:#fff;background:rgba(0,0,0,.88);border:1px solid rgba(255,255,255,.2);box-shadow:0 10px 25px rgba(0,0,0,.3)}.speed-menu{width:min(114px,calc(100% - 24px))}.quality-menu{width:min(168px,calc(100% - 24px))}.rate-choice{justify-content:space-between;width:100%;padding:0 14px;font-size:12px;min-height:36px;gap:8px}.chosen{background:rgba(255,255,255,.12)}.text-button{font-family:inherit;font-size:12px;font-weight:400}.quality-button{width:56px;max-width:64px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;display:block;padding:0 4px}@media(pointer:coarse){.rate-choice{min-height:44px}}@media(max-width:480px){.video-control-bar{padding:.4px 2px}.right-controls{gap:0}.quality-button{width:44px}}
@media(max-width:480px){.danmaku-retry-anchor{display:none}}@media(pointer:fine) and (max-width:480px){button{min-width:32px}.quality-button{width:44px}}
</style>
