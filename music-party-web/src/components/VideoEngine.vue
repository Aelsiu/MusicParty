<template>
  <div ref="surface" data-guide="video-surface" tabindex="0" aria-label="视频画面，按 Tab 进入播放操作" class="video-surface" :class="{'web-fullscreen':pageMode==='WEB_FULLSCREEN','has-notices':hasNotices,'has-top-notice':(dashAudioError||extensionNotice)&&!player.policyRecoveryRequired}"
       :style="{'--video-control-height':`${controlHeight}px`,'--video-surface-height':`${surfaceHeight}px`,'--video-room-footer-height':`${expanded?footerHeight:0}px`,'--video-top-notice-offset':`${topNoticeHeight?topNoticeHeight+6:0}px`}"
       @pointerenter="controls.pointerEnter" @pointermove="surfaceMove" @pointerleave="surfaceLeave"
       @pointerdown="surfaceDown" @pointerup="surfaceUp" @pointercancel="surfaceCancel" @keydown="gesture.keyDown"
       @focusin="focusChanged" @focusout="focusChanged">
    <video ref="videoRef" v-show="!embeddedMedia" class="w-full h-full object-contain" playsinline preload="auto"
           :disablePictureInPicture.prop="mobile" @loadedmetadata="nativeEvent('loadedmetadata',$event)" @canplay="nativeEvent('canplay',$event)" @playing="nativeEvent('playing',$event)"
           @waiting="nativeEvent('waiting',$event)" @stalled="nativeEvent('stalled',$event)" @progress="nativeEvent('progress',$event)" @timeupdate="nativeEvent('timeupdate',$event)" @seeked="nativeEvent('seeked',$event)"
           @play="nativeEvent('play',$event)" @pause="nativeEvent('pause',$event)" @ended="nativeEvent('ended',$event)" @error="nativeEvent('error',$event)"
           @enterpictureinpicture="nativeEvent('enterpictureinpicture',$event)" @leavepictureinpicture="nativeEvent('leavepictureinpicture',$event)"
           @ratechange="!embeddedMedia && videoRef && videoRef.playbackRate !== player.playbackRate && (videoRef.playbackRate = player.playbackRate)" />
    <div v-show="embeddedMedia" data-mirth-extension-player class="embedded-host" />
    <div v-if="!player.nowPlaying" class="absolute inset-0 flex items-center justify-center text-white/70 text-sm p-6 text-center">
      {{player.isLoading?'房间正在解析视频源…':'点播视频，然后手动播放'}}
    </div>
    <VideoDanmakuOverlay :entries="danmaku.visible.value" :media-time="danmaku.mediaTime.value" :settings="ui.danmakuSettings" @resize="danmakuViewport=$event" />
    <VideoAutoAdvanceOverlay v-if="player.synchronized&&player.videoAutoAdvance" :transition="player.videoAutoAdvance" />
    <div v-if="(dashAudioError||extensionNotice)&&!player.policyRecoveryRequired" ref="topNotice" class="video-status-top" data-video-interaction>
      <div v-if="dashAudioError" class="video-notice video-playback-notice dash-audio-notice" role="alert">
        <span>DASH 音轨直连错误，请刷新视频源、检查网络或尝试使用扩展播放</span>
        <div class="notice-actions"><button v-if="suggestEmbedded" @click="toggleEmbedded" :disabled="!canSwitchPlayer">使用扩展</button><button @click="refresh" :disabled="!canRefresh">{{retryRemaining?`${retryRemaining} 秒后可刷新`:'刷新源'}}</button></div>
      </div>
      <div v-if="extensionNotice" class="video-notice video-playback-notice dash-audio-notice extension-notice" role="alert">
        <span>{{ errorMessage }}</span><div class="notice-actions"><button @click="recoverExtension({manual:true})" :disabled="extensionRecovering||!player.synchronized">{{extensionRecovering?'正在重连…':'重试连接'}}</button></div>
      </div>
    </div>
    <div class="video-status-stack" :class="{'with-room-footer':expanded,'menu-open':!!menu}" data-video-interaction>
      <div v-if="player.videoNotification" class="video-notice" role="status"><span>{{player.videoNotification.message}}</span><button aria-label="关闭播放器通知" @click="player.clearVideoNotice"><X class="w-4 h-4"/></button></div>
      <div v-if="player.policyRecoveryRequired" class="video-notice" role="alert"><span>系统分辨率上限下没有可用视频源。上限调整后须手动恢复。</span><button @click="player.recoverVideoSource" :disabled="!player.synchronized||player.isLoading">重新获取视频源</button></div>
      <div v-else-if="errorMessage&&!dashAudioError&&!extensionNotice" class="video-notice" role="alert"><span>{{errorMessage}}</span><button @click="refresh" :disabled="!canRefresh">{{retryRemaining?`${retryRemaining} 秒后可刷新`:'刷新源'}}</button></div>
      <div v-if="presentationError" class="video-notice" role="status"><span>{{presentationError}}</span><button aria-label="关闭呈现提示" @click="presentationError=''"><X class="w-4 h-4"/></button></div>
      <div v-if="oldBridge" class="video-notice" role="status"><span>VIDEO 通知控制需要升级 APK</span></div>
      <div v-if="danmaku.incident.value" class="video-notice danmaku-notice" role="status"><button aria-label="关闭本次弹幕提示" @click="danmaku.dismiss"><X class="w-4 h-4"/></button><span>{{danmaku.incident.value.message}}</span><button @click="danmaku.retry" :disabled="!danmaku.retryEnabled.value">{{danmaku.retryRemainingMs.value?`${Math.ceil(danmaku.retryRemainingMs.value/1000)} 秒后可重试`:'重试弹幕'}}</button></div>
      <div v-if="player.videoWait" class="video-notice video-playback-notice video-buffer-notice" role="status">
        <p>{{player.videoWait.initial?'正在等待全员缓冲，准备统一播放':'有人加载较慢，请等待缓冲'}}</p>
        <p><span class="video-notice-accent">{{player.videoWait.readyCount}}/{{player.videoWait.participantCount}}已就绪</span></p>
        <p v-if="!player.videoWait.initial">最多等待 <span class="video-notice-accent">{{waitRemaining}}</span> 秒</p>
      </div>
    </div>
    <button v-if="needsInteraction&&!player.isPaused&&!player.videoAutoAdvance" @click="recoverPlayback" data-video-interaction class="gesture-recovery bg-accent text-white font-bold px-6 py-3 shadow-lg">点击开始观看 / 恢复观看</button>
    <div class="video-overlay-dock" :class="{shown:controls.visible.value}" :inert="!controls.visible.value" :aria-hidden="!controls.visible.value">
      <VideoControlBar @height="controlHeight=$event" :recovering="recovering" :embedded-eligible="embeddedEligible||!!embeddedMedia" :embedded-active="!!embeddedMedia" :can-switch-player="canSwitchPlayer" @player="toggleEmbedded" :danmaku-capable="!isXifan" :danmaku-enabled="danmaku.enabled.value"
                       :danmaku-retryable="danmaku.retryable.value" :danmaku-retry-enabled="danmaku.retryEnabled.value" :danmaku-retry-remaining="Math.ceil(danmaku.retryRemainingMs.value/1000)" @danmaku-retry="danmaku.retry" :rate="player.playbackRate" :mobile="mobile" :pip-supported="pipSupported && !embeddedMedia" :pip-active="pipActive"
                       :page-mode="pageMode" :request-pending="requestPending" :menu="menu" :can-manage="canManage"
                       :can-switch-page="canManage&&!player.isSkipLocked" :pages="pages" :current-page="player.nowPlaying?.videoDescriptor?.page"
                       :room-status="roomStatus" :retry-remaining="retryRemaining" :can-refresh="canRefresh"
                       :quality-label="player.actualVideoQuality" :quality-options="qualityOptions" :quality-preference="ui.videoQualityPreference" :quality-enabled="qualityEnabled"
                       @danmaku="danmaku.enabled.value=!danmaku.enabled.value"
                       @menu="menu=$event" @rate="chooseRate" @quality="chooseQuality" @refresh="refresh"
                       @pip="togglePiP" @web="toggleWebFullscreen" @fullscreen="requestFullscreen" />
      <div ref="expandedDock" class="expanded-room-dock" data-video-interaction></div>
    </div>
  </div>
</template>
<script setup>
import { computed, inject, ref, shallowRef, nextTick, watch, onMounted, onBeforeUnmount } from 'vue';
import { createEmbeddedMedia } from '../utils/embeddedMedia.js';
import { isEmbeddedPlayerEligible } from '../utils/embeddedPlayerClient.js';
import { X } from 'lucide-vue-next';
import { roomSession } from '../services/roomSession';
import { usePlayerStore } from '../stores/player';
import { useUiStore } from '../stores/ui';
import { useUserStore } from '../stores/user';
import { useGuideStore } from '../stores/guide.js';
import { useVideo } from '../composables/useVideo';
import { useDanmaku } from '../composables/useDanmaku';
import { useVideoSurfaceGesture, isVideoInteraction } from '../composables/useVideoSurfaceGesture';
import { useVideoControlsVisibility } from '../composables/useVideoControlsVisibility';
import { useVideoPresentation } from '../composables/useVideoPresentation';
import { videoPresentationKey } from '../composables/videoPresentationContext';
import VideoDanmakuOverlay from './VideoDanmakuOverlay.vue';
import VideoAutoAdvanceOverlay from './VideoAutoAdvanceOverlay.vue';
import VideoControlBar from './VideoControlBar.vue';
const props=defineProps({pages:{type:Array,default:()=>[]},retryRemaining:{type:Number,default:0},waitRemaining:{type:Number,default:0},roomStatus:{type:String,default:''}});
const player=usePlayerStore(),ui=useUiStore(),user=useUserStore(),videoRef=ref(null),surface=ref(null),expandedDock=ref(null),menu=ref('');
const guide=useGuideStore();
const mobile=!!window.AndroidBridge||/Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
const isXifan=computed(()=>player.nowPlaying?.videoDescriptor?.provider==='xifan');
const embeddedEligible=computed(()=>!isXifan.value&&isEmbeddedPlayerEligible({mobile,origin:window.location?.origin,userAgent:navigator.userAgent}));
const embeddedMedia=shallowRef(null),mediaRef=computed(()=>embeddedMedia.value||videoRef.value);
const oldBridge=!!window.AndroidBridge&&Number(window.AndroidBridge.getBridgeVersion?.()||0)<2;
const context=inject(videoPresentationKey,null);
const controls=useVideoControlsVisibility({mobile});
watch(()=>guide.main,value=>controls.setGuideHold(value),{immediate:true,flush:'sync'});
const presentation=useVideoPresentation(surface,videoRef,{expandedDock,context,player,mobile,
    closeMenu:()=>{if(!menu.value)return false;menu.value='';return true;},onModeChange:()=>controls.reveal(),onRevealControls:controls.reveal});
const {ownerToken,pageMode,pipActive,pipSupported,requestPending,presentationError,toggleWebFullscreen,requestFullscreen,togglePiP}=presentation;
const expanded=computed(()=>pageMode.value!=='INLINE');
const controlHeight=ref(44.8),surfaceHeight=ref(360),footerHeight=ref(0),topNotice=ref(null),topNoticeHeight=ref(0);let sizeObserver;
watch(topNotice,(target,previous)=>{if(previous)sizeObserver?.unobserve?.(previous);topNoticeHeight.value=target?.getBoundingClientRect?.().height||0;if(target)sizeObserver?.observe(target);},{flush:'post'});
const danmakuViewport=ref({width:640,height:360});
const danmaku=useDanmaku(mediaRef,player,{settings:computed(()=>ui.danmakuSettings),viewport:danmakuViewport});
const videoEngine=useVideo(mediaRef,player,ui);
const {extensionFault=ref(''),extensionRecovering=ref(false),recoverExtension=()=>{}}=videoEngine;
const extensionNotice=computed(()=>extensionFault.value.startsWith('EXTENSION_'));
const {needsInteraction,errorMessage,recovering,recoverPlayback,manualRefresh,onEnterPiP,onLeavePiP,onNativePlay,onReady,onWaiting,onStalled,onProgress,onTimeUpdate,onPlaying,onPause,onEnded,onError}=videoEngine;
const mediaEvents={loadedmetadata:onReady,canplay:onReady,seeked:onReady,progress:onProgress,play:onNativePlay,playing:onPlaying,pause:onPause,waiting:onWaiting,stalled:onStalled,timeupdate:onTimeUpdate,ended:onEnded,error:onError,enterpictureinpicture:onEnterPiP,leavepictureinpicture:onLeavePiP};
function nativeEvent(type,event){if(!embeddedMedia.value)mediaEvents[type]?.(event);}
let preferEmbedded=false;
watch(isXifan,value=>{if(value&&embeddedMedia.value){preferEmbedded=true;embeddedMedia.value.dispose();embeddedMedia.value=null;}else if(!value&&preferEmbedded){nextTick(()=>{if(!isXifan.value&&canSwitchPlayer.value&&!embeddedMedia.value)toggleEmbedded();});}},{flush:'sync'});
async function toggleEmbedded(){
    if(!canSwitchPlayer.value)return;
    menu.value='';controls.reveal();
    if(embeddedMedia.value){preferEmbedded=false;embeddedMedia.value.dispose();embeddedMedia.value=null;return;}
    if(isXifan.value)return;
    preferEmbedded=true;
    const media=createEmbeddedMedia({onEvent:type=>{if(embeddedMedia.value===media)mediaEvents[type]?.();}});
    embeddedMedia.value=media;
    embeddedMedia.value.volume=ui.audioVolume;
    videoRef.value?.pause();videoRef.value?.removeAttribute('src');videoRef.value?.load();
    await nextTick();embeddedMedia.value?.connect();
}
const canManage=computed(()=>roomSession.ownerAccess&&!user.isGuest&&player.synchronized&&player.mediaMode==='VIDEO');
const canRefresh=computed(()=>player.synchronized&&!player.isLoading&&props.retryRemaining===0&&!!player.nowPlaying);
const canSwitchPlayer=computed(()=>!pipActive.value&&!requestPending.value&&(!!embeddedMedia.value||(embeddedEligible.value&&player.synchronized&&!!player.nowPlaying)));
const dashAudioError=computed(()=>/DASH 音轨/.test(errorMessage.value));
const suggestEmbedded=computed(()=>embeddedEligible.value&&!embeddedMedia.value&&dashAudioError.value);
const qualityOptions=computed(()=>[...new Map((player.videoQualityOptions || []).map(option=>[option.id,option])).values()].sort((a,b)=>Number(b.id.slice(1))-Number(a.id.slice(1))));
const qualityEnabled=computed(()=>player.synchronized&&!!player.nowPlaying&&qualityOptions.value.length>0);
const hasNotices=computed(()=>!!(player.videoNotification||player.policyRecoveryRequired||errorMessage.value||presentationError.value||oldBridge||danmaku.incident.value||player.videoWait));
let gesture;
watch(menu,value=>controls.setMenuOpen(!!value));
watch(()=>context?.interactionLocked.value,value=>controls.setExternalLock(!!value),{immediate:true,flush:'sync'});
watch(controls.visible,value=>{
    context?.setControlsVisible(ownerToken,value);
    if(!value){menu.value='';context?.closeMenu();gesture?.reset();}
},{immediate:true});
watch(()=>[player.clientGeneration,player.playbackEpoch,player.modeEpoch,player.nowPlaying?.playbackId,roomSession.generation,canManage.value],(value,previous)=>{
    if(value.every((item,index)=>Object.is(item,previous[index])))return;
    menu.value='';controls.reveal();
});
watch(()=>ui.audioVolume,()=>{if(videoRef.value)videoRef.value.muted=false;},{immediate:true});
function chooseRate(value){const request=player.playbackContext();if(!canManage.value||!request||![.5,.75,1,1.25,1.5,1.75,2].includes(value)||!player.isContextCurrent(request))return;player.setVideoRate(value);menu.value='';controls.reveal();}
function chooseQuality(value){if(!qualityEnabled.value||(value!=='auto'&&!qualityOptions.value.some(option=>option.id===value)))return;ui.setVideoQualityPreference(value);menu.value='';controls.reveal();}
function refresh(){if(canRefresh.value){manualRefresh();menu.value='';controls.reveal();}}
gesture = useVideoSurfaceGesture({
    context: () => [roomSession.roomId,roomSession.generation,player.clientGeneration,player.playbackEpoch,player.mediaMode,player.modeEpoch,player.nowPlaying?.playbackId,player.synchronized,pageMode.value,pipActive.value],
    allowed: () => !guide.active && !document.hidden && !menu.value && !context?.interactionLocked.value && !editingFocus(document.activeElement),
    doubleAllowed: () => !guide.active && !document.hidden && !menu.value && !context?.interactionLocked.value && player.synchronized && !!player.nowPlaying,
    intent: () => controls.visible.value ? 'hide' : 'show', onSingle: value => controls.tapBlank(value),
    onDouble: () => {if(!requestPending.value)requestFullscreen();}
});
function surfaceDown(event){interactionStart(event);gesture.pointerDown(event);}
function surfaceMove(event){controls.pointerMove(event);gesture.pointerMove(event);}
function surfaceLeave(event){if(controls.pointerLeave(event)){menu.value='';context?.closeMenu();gesture.reset();}}
function surfaceUp(event){gesture.pointerUp(event);controls.setDragging(false);}
function surfaceCancel(event){gesture.pointerCancel(event);controls.setDragging(false);}
watch(menu,()=>gesture.reset(),{flush:'sync'});
watch(()=>context?.interactionLocked.value,()=>gesture.reset(),{flush:'sync'});
watch(()=>guide.active,()=>gesture.reset(),{flush:'sync'});
let pointerFocus = false;
const editingFocus = target => target?.isContentEditable || target?.tagName === 'TEXTAREA'
    || target?.tagName === 'INPUT' && !['range','checkbox','radio','button','submit','reset'].includes(target.type);
const controlFocus = target => !!target && !!surface.value?.contains(target)
    && (target === surface.value || !!target.closest?.('.video-overlay-dock'));
function interactionStart(event){
    pointerFocus = true;
    controls.setFocusWithin(controlFocus(event.target) && editingFocus(event.target));
    if(event.target.closest?.('[data-video-interaction],button,input,select')){controls.reveal();if(event.target.matches?.('input[type=range],[role=slider]'))controls.setDragging(true);}
}
function focusChanged(event){
    const target = event.type === 'focusout' ? event.relatedTarget : event.target;
    const lock = controlFocus(target) && (!pointerFocus || editingFocus(target));
    // Pointer focus must not hold the bar open after the mouse goes idle.
    // Keyboard entry and active text editing retain accessible controls.
    if(event.type === 'focusin' && lock) controls.keyboardActivity();
    controls.setFocusWithin(lock);
}
function keyboardFocus(event){pointerFocus=false;if(controlFocus(event.target)){controls.keyboardActivity();controls.setFocusWithin(true);}}
function foreground(){gesture.reset();if(!document.hidden)controls.reveal();}
onMounted(()=>{
    if(videoRef.value)videoRef.value.muted=false;
    context?.setControlsVisible(ownerToken,controls.visible.value);
    sizeObserver=new ResizeObserver(entries=>{for(const entry of entries){if(entry.target===surface.value)surfaceHeight.value=entry.contentRect.height;else if(entry.target===expandedDock.value)footerHeight.value=entry.contentRect.height;else if(entry.target===topNotice.value)topNoticeHeight.value=entry.contentRect.height;}});
    sizeObserver.observe(surface.value);sizeObserver.observe(expandedDock.value);
    if(topNotice.value)sizeObserver.observe(topNotice.value);
    document.addEventListener('visibilitychange',foreground);document.addEventListener('keydown',keyboardFocus,true);
});
onBeforeUnmount(()=>{embeddedMedia.value?.dispose();sizeObserver?.disconnect();document.removeEventListener('visibilitychange',foreground);document.removeEventListener('keydown',keyboardFocus,true);});
</script>
<style scoped>
.video-surface{--video-control-height:44.8px;touch-action:manipulation;position:relative;display:flex;flex:1;width:100%;height:100%;min-width:0;min-height:0;align-items:center;justify-content:center;overflow:hidden;background:#000;outline:none}.video-surface.web-fullscreen{position:fixed;inset:0;width:100vw;height:100dvh;z-index:80}.video-surface:fullscreen,.video-surface.web-fullscreen{padding:env(safe-area-inset-top) env(safe-area-inset-right) 0 env(safe-area-inset-left)}.video-overlay-dock{position:absolute;bottom:0;inset-inline:0;opacity:0;transform:translateY(100%);pointer-events:none;transition:transform 200ms,opacity 200ms;z-index:20}.video-overlay-dock.shown{opacity:1;transform:none;pointer-events:auto}.video-surface:fullscreen .video-overlay-dock,.video-surface.web-fullscreen .video-overlay-dock{padding-inline:env(safe-area-inset-left) env(safe-area-inset-right)}.expanded-room-dock:empty{display:none}.video-status-stack{position:absolute;bottom:calc(var(--video-control-height) + 10px);left:12px;right:12px;display:flex;flex-direction:column;gap:6px;max-height:45%;overflow:auto;z-index:10;pointer-events:none}.video-status-stack.with-room-footer{bottom:calc(var(--video-control-height) + 10px + var(--video-room-footer-height) + env(safe-area-inset-bottom))}.video-notice{display:flex;align-items:center;gap:10px;padding:10px;background:rgb(var(--surface)/.92);color:rgb(var(--medical-900));font-size:12px;pointer-events:auto}.video-notice span{flex:1;min-width:0}.video-notice button{color:rgb(var(--accent));flex-shrink:0;min-height:32px}.video-notice button:disabled{opacity:.45}.gesture-recovery{position:absolute;z-index:30}.danmaku-notice>button:first-child{width:32px;display:grid;place-items:center}@media(max-width:767px){.video-status-stack.with-room-footer{bottom:calc(var(--video-control-height) + 10px + var(--video-room-footer-height) + env(safe-area-inset-bottom))}.video-notice{font-size:11px}}@media(prefers-reduced-motion:reduce){.video-overlay-dock{transition:none}}
.video-surface{--video-menu-reserved-height:0px;--video-menu-max-height:max(44px,min(330px,calc(var(--video-surface-height) - var(--video-room-footer-height) - var(--video-control-height) - 12px - var(--video-menu-reserved-height))))}.video-surface:focus-visible{outline:2px solid rgb(var(--accent));outline-offset:-2px}.video-status-stack.menu-open{right:252px}.video-status-stack.menu-open .video-notice{flex-wrap:wrap}.video-status-stack.menu-open .video-notice button{max-width:100%;white-space:normal}@media(max-width:767px) and (orientation:portrait){.video-surface.has-notices{--video-menu-reserved-height:80px}.video-status-stack.menu-open{left:12px;right:12px;top:12px;bottom:auto;max-height:max(0px,calc(var(--video-surface-height) - var(--video-room-footer-height) - 80px - var(--video-menu-max-height)))}}
.embedded-host{position:absolute;inset:0;width:100%;height:100%;min-width:0;min-height:0;overflow:hidden;pointer-events:none}.embedded-host :deep(iframe){position:absolute;inset:0;display:block;width:100%;height:100%;border:0}.danmaku-notice{width:fit-content;align-self:flex-start;max-width:100%;flex-wrap:wrap}.danmaku-notice span{white-space:normal;overflow-wrap:anywhere}
.video-status-top{position:absolute;top:12px;left:12px;right:12px;z-index:10;max-height:45%;overflow:auto;pointer-events:none}.video-playback-notice{background:rgba(0,0,0,.5);color:#fff}.dash-audio-notice{flex-wrap:wrap;align-items:flex-start}.dash-audio-notice>span{flex:1 1 280px;overflow-wrap:anywhere;align-self:center}.notice-actions{display:flex;gap:12px;flex:none;align-items:center}.video-buffer-notice{display:flex;flex-direction:column;align-items:flex-start;gap:6px;width:fit-content;max-width:100%;overflow-wrap:anywhere}.video-buffer-notice p{margin:0}.video-notice-accent{color:rgb(var(--accent));font-variant-numeric:tabular-nums}
@media(max-width:767px) and (orientation:portrait){.video-surface.has-top-notice{--video-menu-reserved-height:calc(80px + var(--video-top-notice-offset))}.video-surface.has-top-notice .video-status-stack.menu-open{top:calc(12px + var(--video-top-notice-offset));max-height:max(0px,calc(var(--video-surface-height) - var(--video-room-footer-height) - var(--video-control-height) - 12px - var(--video-menu-max-height) - var(--video-top-notice-offset)))}}
</style>
<style scoped>
.video-notice{background:rgba(0,0,0,.5);color:#fff}
</style>
