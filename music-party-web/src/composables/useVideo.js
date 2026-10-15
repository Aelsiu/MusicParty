import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { chooseDashTracks, selectVideoQualityTracks, createDashManifest, playableAt } from '../utils/videoManifest.js';
import { selectReachableAudio, reachableVideoTracks, createDashCdnRouter } from '../utils/videoCdn.js';
import { claimMediaOwner, clearMediaActions, nextMediaPublication } from '../utils/mediaOwner.js';
import { roomSession } from '../services/roomSession.js';
import { markVideoAlignment } from '../utils/videoAlignment.js';
import { embeddedPlayerIdentity } from '../utils/embeddedPlayerClient.js';
import { readVideoBufferedRanges } from '../utils/videoBuffer.js';
import { useGuideStore } from '../stores/guide.js';

// One element follows the room; native events never send playback controls.
export function useVideo(videoRef, player, ui, options = {}) {
    const guide = useGuideStore();
    const needsInteraction = ref(false), errorMessage = ref('');
    const extensionFault = ref(''), extensionRecovering = ref(false);
    let frameOwnerId='',presentationMedia=null;
    watch([videoRef,()=>roomSession.generation,()=>roomSession.roomId,()=>player.clientGeneration,()=>player.playbackEpoch,()=>player.modeEpoch,()=>player.nowPlaying?.playbackId,()=>player.nowPlaying?.videoDescriptor?.page,()=>player.seekRevision,()=>player.mediaMode],()=>{
        presentationMedia?.setPresentationOwner?.('');
        presentationMedia=videoRef.value;frameOwnerId=embeddedPlayerIdentity();
        presentationMedia?.setPresentationOwner?.(player.mediaMode==='VIDEO'?frameOwnerId:'');
    },{immediate:true,flush:'sync'});
    watch(()=>player.isPaused,value=>videoRef.value?.setRetainPausedFrame?.(value),{immediate:true,flush:'sync'});
    let extensionRecovery=null,extensionAttempted='',extensionAttemptedMedia=null;
    const extensionContext=()=>[roomSession.generation,roomSession.roomId,player.clientGeneration,player.playbackEpoch,player.modeEpoch,player.nowPlaying?.playbackId,player.nowPlaying?.videoDescriptor?.page,player.nowPlaying?.sourceRevision];
    async function recoverExtension({manual=false}={}) {
        const media=videoRef.value;
        if(!active()||!media?.embedded||!hasSource()||sourceExpired())return false;
        if(extensionRecovery)return extensionRecovery.promise;
        const key=sourceKey();
        if(!manual&&(extensionFault.value==='EXTENSION_REVOKED'||extensionAttempted===key&&extensionAttemptedMedia===media))return false;
        extensionAttempted=key;extensionAttemptedMedia=media;
        const captured=extensionContext(),operation={};extensionRecovery=operation;extensionRecovering.value=true;
        const current=()=>alive&&active()&&extensionRecovery===operation&&videoRef.value===media&&captured.every((value,index)=>value===extensionContext()[index]);
        operation.promise=(async()=>{
            recoverPlayback();
            for(let attempt=0;attempt<2;attempt++) {
                if(!current())return false;
                media.resetConnection();media.connect();
                const loaded=await loadSource();
                if(!current())return false;
                if(loaded){extensionFault.value='';errorMessage.value='';player.isErrorState=false;reportStatus();return true;}
                if(extensionFault.value==='EXTENSION_REVOKED')break;
                if(attempt===0)await new Promise(resolve=>setTimeout(resolve,1000));
            }
            if(current()){extensionFault.value=extensionFault.value||'EXTENSION_UNAVAILABLE';errorMessage.value='扩展连接恢复失败，请启用扩展并重试连接';reportStatus();}
            return false;
        })().finally(()=>{if(extensionRecovery===operation){extensionRecovery=null;extensionRecovering.value=false;}});
        return operation.promise;
    }
    const recovering = ref(false);
    const backgroundPolicyBlocked = ref(false);
    let backgroundRecoveryTransition = 0;
    let recoveryGeneration = 0, recovery = null;
    const mediaOwner = claimMediaOwner(() => { videoRef.value?.pause(); clearMediaActions(); });
    let mediaPublished = '';
    const ownerToken = `video-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    let actionSequence = -1;
    const bridgeGeneration = Number(globalThis.window?.AndroidBridge?.getBridgeGeneration?.() || 0);
    const bridgeContext = () => ({ roomId: roomSession.roomId, clientGeneration: player.clientGeneration || 0,
        playbackEpoch: player.playbackEpoch, modeEpoch: player.modeEpoch || 0, bridgeVersion: 2, bridgeGeneration, mediaMode: 'VIDEO',
        playbackId: player.nowPlaying?.playbackId, rendererGeneration: 1, ownerToken });
    const nativeControl = raw => {
        let envelope; try { envelope = JSON.parse(raw); } catch { return; }
        const current = bridgeContext();
        if (!active() || !Object.keys(current).every(key => envelope[key] === current[key])
            || !Number.isSafeInteger(envelope.actionSequence) || envelope.actionSequence <= actionSequence) return;
        actionSequence = envelope.actionSequence;
        if (guide.active) return;
        if (envelope.action === 'play') onNativePlay();
    };
    let lifecycleToken = 0, alive = true, pendingPosition = true, playPending = false;
    let syncTimer, watchTimer, stallTimer, endedTimer, refreshedSource = '', programmaticPause = false;
    let observedPosition = NaN, lastAdvanceAt = 0, bufferTimeoutError = false;
    let dashPlayer = null, manifestUrl = null, localSourceGeneration = 0, sequence = 0;
    let bufferedSourceKey = '', bufferedMedia = null;
    let playbackAllowed = false, qualifiedInstance = '', delivery = '', loadingSource = false, fallbackUsed = false;
    let recoverableNetworkError = false;
    let frozenInstance = '', frozenPosition = NaN;
    let sourceAbort = null;
    let loadedSourceKey = '';
    let dashFailureTimer = null;
    let diskStartupTimer = null, diskStartupAttempt = '';
    let forgetAlignment = null;
    let pipActive = false, pipExiting = false, pipPauseTimer;
    const sourceKey = () => `${player.clientGeneration || 0}:${player.playbackEpoch}:${player.nowPlaying?.playbackId}:${player.nowPlaying?.sourceRevision || 0}`;
    const instanceKey = () => `${player.playbackEpoch}:${player.modeEpoch}:${player.nowPlaying?.playbackId}`;
    const active = () => alive && mediaOwner.current() && player.mediaMode === 'VIDEO' && player.synchronized;
    const bufferKey = () => `${roomSession.generation}:${roomSession.roomId}:${player.modeEpoch}:${sourceKey()}`;
    const setBufferedRanges = ranges => {
        if (!mediaOwner.current()) return;
        const previous = player.videoBufferedRanges;
        if (!Array.isArray(previous) || previous.length !== ranges.length
            || ranges.some((range, index) => range[0] !== previous[index][0] || range[1] !== previous[index][1])) player.videoBufferedRanges = ranges;
    };
    const onProgress = () => setBufferedRanges(active() && !sourceExpired() && bufferedMedia === videoRef.value && bufferedSourceKey === bufferKey()
        ? readVideoBufferedRanges(videoRef.value, player.nowPlaying?.music?.duration) : []);
    const waiting = () => !!player.videoWait;
    const sourceExpired = () => player.nowPlaying?.sourceExpiresAt != null && player.nowPlaying.sourceExpiresAt <= Date.now();
    const effectivePaused = () => recovering.value || backgroundPolicyBlocked.value || player.isPaused || !!player.videoAutoAdvance || waiting() || sourceExpired();
    const rate = () => player.playbackRate || 1;
    const roomWantsPlay = () => player.playIntent ? player.playIntent === 'PLAYING' : !player.isPaused;
    const hasSource = () => !!(player.nowPlaying?.videoSource?.dash || player.nowPlaying?.videoSource?.mp4?.url || player.nowPlaying?.videoSource?.url || player.nowPlaying?.music?.url);
    const targetPosition = () => Math.max(0, waiting() ? player.videoWait.frozenPosition / 1000 : player.getCurrentProgress() / 1000);
    const allowsRoomAlignment = () => !recovering.value;
    const freezeAtCurrentPosition = () => {
        if (!player.nowPlaying?.playbackId) return;
        frozenInstance = instanceKey();
        const position = videoRef.value?.currentTime;
        frozenPosition = Number.isFinite(position) ? Math.max(0, position) : targetPosition();
    };
    const alignmentPosition = followRoom => {
        if (followRoom || allowsRoomAlignment()) return targetPosition();
        // Recovery keeps the same instance's silent anchor until fresh state,
        // clock and decoded source allow following the room again.
        if (frozenInstance !== instanceKey() || !Number.isFinite(frozenPosition)) {
            frozenInstance = instanceKey(); frozenPosition = targetPosition();
        }
        return frozenPosition;
    };
    const clearBuffering = () => {
        clearTimeout(stallTimer); stallTimer = null; player.isBuffering = false;
        if (bufferTimeoutError) { errorMessage.value = ''; bufferTimeoutError = false; }
    };
    const clearTimers = () => { clearBuffering(); clearTimeout(endedTimer); clearTimeout(dashFailureTimer); dashFailureTimer = null;
        clearTimeout(diskStartupTimer); diskStartupTimer = null; };
    const expectsProgress = () => active() && !effectivePaused() && !videoRef.value?.paused
        && !videoRef.value?.ended && !needsInteraction.value && !player.isErrorState;
    const pauseElement = () => { const video = videoRef.value; if (!video) return; if (!video.paused) programmaticPause = true; video.pause(); };
    const recoverPlayback = (options = {}) => {
        if (!active()) return;
        if (options.automaticBackground && backgroundPolicyBlocked.value) return;
        if (!options.automaticBackground) backgroundPolicyBlocked.value = false;
        freezeAtCurrentPosition();
        recovering.value = true; playbackAllowed = false;
        recovery = { generation: ++recoveryGeneration, client: player.clientGeneration,
            count: player.completeStateCount, revision: player.stateRevision };
        clearBuffering(); pauseElement(); reportStatus();
        player.sampleClock(recovery.generation); player.resync();
    };
    const recoverAndroidBackgroundPause = () => {
        const bridge = globalThis.window?.AndroidBridge;
        if (!document.hidden || Number(bridge?.getBridgeVersion?.()) !== 2 || !bridge.getBackgroundTransition) return false;
        let transition;
        try { transition = JSON.parse(bridge.getBackgroundTransition()); } catch { return false; }
        if (transition.bridgeGeneration !== bridgeGeneration || transition.background !== true
            || !Number.isSafeInteger(transition.transitionId) || transition.transitionId <= 0) return false;
        if (transition.transitionId === backgroundRecoveryTransition) {
            backgroundPolicyBlocked.value = true; playbackAllowed = false; clearBuffering(); reportStatus();
            return true;
        }
        if (!Number.isFinite(transition.ageMs) || transition.ageMs < 0 || transition.ageMs > 2000
            || !playbackAllowed || qualifiedInstance !== instanceKey()) return false;
        // The native transition permits one fresh recovery, never an autoplay loop.
        backgroundRecoveryTransition = transition.transitionId;
        recoverPlayback({ automaticBackground: true });
        return true;
    };
    const checkRecovery = () => {
        if (!recovery || !active() || recovery.client !== player.clientGeneration) return;
        if (player.completeStateCount <= recovery.count || player.stateRevision <= recovery.revision
            || !player.clockReady || player.clockRecoveryGeneration !== recovery.generation
            || loadedSourceKey !== sourceKey() || loadingSource || !hasSource() || sourceExpired()
            || !videoRef.value || videoRef.value.readyState < 3) return;
        if (!recovery.aligned || Math.abs(videoRef.value.currentTime - targetPosition()) > .5) {
            pendingPosition = true; align(true, true); recovery.aligned = !pendingPosition;
        }
        if (pendingPosition || videoRef.value.seeking || videoRef.value.readyState < 3
            || Math.abs(videoRef.value.currentTime - targetPosition()) > .5) return;
        recovery = null; recovering.value = false;
        if (!effectivePaused()) playLocal();
    };
    const align = (immediate = false, followRoom = false) => {
        const video = videoRef.value;
        if (!active() || !video || !player.nowPlaying || video.readyState < 1) { pendingPosition = true; return; }
        const frozen = !allowsRoomAlignment();
        if (video.seeking && !pendingPosition && !frozen) return;
        let target = alignmentPosition(followRoom);
        const duration = Number.isFinite(video.duration) ? video.duration : player.nowPlaying.music.duration / 1000;
        if (duration > 0) target = Math.min(target, Math.max(0, duration - .001));
        if (video.seekable.length) {
            let nearest = null;
            for (let i = 0; i < video.seekable.length; i++) {
                const start = video.seekable.start(i), rawEnd = video.seekable.end(i);
                // Progressive sources may expose [0, 0] while decoded media is already
                // playing. An empty range cannot constrain every alignment back to zero.
                if (!Number.isFinite(start) || !Number.isFinite(rawEnd) || rawEnd <= start) continue;
                const end = Math.max(start, rawEnd - .001);
                const candidate = Math.max(start, Math.min(end, target));
                if (nearest === null || Math.abs(candidate - target) < Math.abs(nearest - target)) nearest = candidate;
            }
            if (nearest !== null) target = nearest;
        }
        // Readiness, room seek/rate/wait and native seeks all pass this same gate.
        // Once a seekable range constrains a frozen reload, retain that finite
        // correction instead of chasing the room as more ranges become available.
        if (frozen) frozenPosition = target;
        const threshold = pendingPosition || frozen ? .001 : immediate ? .05 : effectivePaused() ? .2 : .5;
        // A cold progressive download must finish its first seek and start decoding.
        // Re-seeking on its canplay event can otherwise chase a moving room forever,
        // before actual playing qualifies this viewer for the existing wait barrier.
        const awaitingFirstMp4Play = delivery === 'mp4' && qualifiedInstance !== instanceKey()
            && !pendingPosition && !followRoom && !effectivePaused();
        const needsSeek = !awaitingFirstMp4Play && Math.abs(video.currentTime - target) > threshold;
        const continuousAlignment = !pendingPosition && !followRoom && !frozen;
        pendingPosition = false;
        if (needsSeek) {
            observedPosition = target;
            forgetAlignment?.();
            forgetAlignment = continuousAlignment ? markVideoAlignment(video, video.currentTime, target) : null;
            try { video.currentTime = target; } catch { forgetAlignment?.(); pendingPosition = true; }
        }
        if (video.playbackRate !== rate()) video.playbackRate = rate();
        player.localProgress = video.currentTime * 1000;
    };
    const reportStatus = () => {
        if (!active() || !player.nowPlaying || !player.reportVideoWatch) return;
        const video = videoRef.value;
        let status = 'PREPARING';
        const foreground = !document.hidden;
        if (!foreground) status = 'INACTIVE';
        else if (recovering.value) status = 'PREPARING';
        else if (player.isErrorState && !recoverableNetworkError) status = 'ERROR';
        else if (player.videoWait?.initial) status = !sourceExpired() && playableAt(video, targetPosition(), rate(), dashPlayer) && !pendingPosition && !video?.seeking ? 'READY' : 'PREPARING';
        else if (needsInteraction.value || !playbackAllowed) status = 'BLOCKED';
        else if (playableAt(video, waiting() ? targetPosition() : video.currentTime, rate(), dashPlayer)) status = 'READY';
        else if (roomWantsPlay() && (player.isBuffering || waiting() || loadingSource || recoverableNetworkError)) status = 'BUFFERING';
        player.reportVideoWatch({ status, sourceGeneration: localSourceGeneration, sequence: ++sequence,
            synchronizedWithRoom: player.synchronized && !!video && (qualifiedInstance === instanceKey()
                || (!pendingPosition && Math.abs(video.currentTime - targetPosition()) <= .75)),
            foreground, playbackAllowed: playbackAllowed && !recovering.value, positionMs: Math.round((video?.currentTime || 0) * 1000),
            waitId: player.videoWait?.waitId ?? null, waitRevision: player.videoWait?.waitRevision ?? null });
    };
    const publishMedia = () => {
        const publication = `${sourceKey()}:${player.modeEpoch}:${player.synchronized}:${videoRef.value?.paused}:${Math.floor((videoRef.value?.currentTime || 0)/15)}`;
        if (!alive || !mediaOwner.current() || player.mediaMode !== 'VIDEO' || publication === mediaPublished) return;
        mediaPublished = publication;
        const bridge = globalThis.window?.AndroidBridge;
        if (player.nowPlaying && Number(bridge?.getBridgeVersion?.()) >= 2) {
            bridge.updateMedia(JSON.stringify({ bridgeVersion: 2, bridgeGeneration, publicationSequence: nextMediaPublication(), mediaMode: 'VIDEO', context: bridgeContext(),
                title: player.nowPlaying.music?.name || 'MirthParty', artist: `P${player.nowPlaying.videoDescriptor?.page || 1}`,
                paused: !!videoRef.value?.paused, position: Math.round(videoRef.value?.currentTime || 0),
                duration: Math.round((player.nowPlaying.music?.duration || 0) / 1000), coverUrl: player.nowPlaying.music?.coverUrl || '' }));
        }
        const session = globalThis.navigator?.mediaSession;
        if (!session || !active() || !player.nowPlaying) return;
        const key = `${sourceKey()}:${player.modeEpoch}`, generation = lifecycleToken;
        const valid = () => active() && generation === lifecycleToken && key === `${sourceKey()}:${player.modeEpoch}`;
        clearMediaActions();
        if (globalThis.MediaMetadata) session.metadata = new MediaMetadata({ title: player.nowPlaying.music?.name || 'MirthParty',
            artist: `P${player.nowPlaying.videoDescriptor?.page || 1}`, artwork: player.nowPlaying.music?.coverUrl ? [{src:player.nowPlaying.music.coverUrl}] : [] });
        session.playbackState = videoRef.value?.paused ? 'paused' : 'playing';
        try {
            session.setActionHandler('play', () => { if (valid() && !guide.active) onNativePlay(); });
        } catch {}
    };
    const playLocal = async () => {
        const video = videoRef.value;
        if(active()&&video?.embedded&&video.connectionState==='SUSPENDED'&&roomWantsPlay()) { await recoverExtension();return; }
        if (!active() || !video || effectivePaused() || !hasSource() || playPending || dashFailureTimer || (loadingSource && delivery === 'dash') || errorMessage.value) return;
        if (!video.paused) { align(); return; }
        const token = lifecycleToken;
        playPending = true; align(true);
        try {
            await video.play();
            if (!alive) { video.pause(); return; }
            if (token !== lifecycleToken) { if (effectivePaused() || !active()) pauseElement(); return; }
            if (effectivePaused() || !active()) { pauseElement(); return; }
            needsInteraction.value = false; playbackAllowed = true; qualifiedInstance = instanceKey();
            programmaticPause = false; align(); reportStatus();
        } catch (error) {
            if (!active() || token !== lifecycleToken) return;
            if (document.hidden && backgroundRecoveryTransition > 0 && window.AndroidBridge?.getBackgroundTransition) {
                backgroundPolicyBlocked.value = true; needsInteraction.value = true;
                playbackAllowed = false; clearBuffering(); reportStatus();
            }
            if (error.name === 'NotAllowedError') { extensionFault.value='AUTOPLAY_BLOCKED';needsInteraction.value = true; playbackAllowed = false; clearBuffering(); reportStatus(); }
            else if (error.name !== 'AbortError') { errorMessage.value = '本端无法播放，请检查网络或更换浏览器'; player.isErrorState = true; reportStatus(); }
        } finally { if (token === lifecycleToken) playPending = false; }
    };
    const refreshAfterError = reason => {
        if (!active() || !player.nowPlaying || refreshedSource === sourceKey()) return;
        if (player.sourceRetryAt && player.sourceRetryAt > Date.now()) return;
        if (player.refreshVideoSource(reason)) refreshedSource = sourceKey();
    };
    const destroyDash = () => {
        if (dashPlayer) { try { dashPlayer.reset(); } catch {} dashPlayer = null; }
        if (manifestUrl) { URL.revokeObjectURL(manifestUrl); manifestUrl = null; }
    };
    const fallbackMp4 = token => {
        const source = player.nowPlaying?.videoSource;
        const url = source?.mp4?.url || source?.url || (!source?.dash && player.nowPlaying?.music?.url);
        if (!url || token !== lifecycleToken || fallbackUsed || !alive) return false;
        fallbackUsed = true; lifecycleToken++; localSourceGeneration++; playPending = false;
        clearTimers();
        sourceAbort?.abort(); sourceAbort = null;
        setBufferedRanges([]);
        destroyDash(); delivery = 'mp4'; player.videoDelivery = 'MP4';
        player.actualVideoQuality = source?.mp4?.qualityLabel || (source?.quality ? `MP4 · ${source.quality}` : 'MP4');
        player.videoQualityOptions = [];
        pendingPosition = true; loadingSource = true; recoverableNetworkError = false; errorMessage.value = ''; player.isErrorState = false;
        pauseElement(); videoRef.value.src = url; videoRef.value.load(); reportStatus();
        return true;
    };
    const retryDiskStartup = () => {
        const video=videoRef.value,source=player.nowPlaying?.videoSource;
        if(delivery!=='mp4'||video?.embedded||video?.error?.code!==4||video.readyState!==0
            ||player.nowPlaying?.videoDescriptor?.provider!=='xifan'||qualifiedInstance===instanceKey()||sourceExpired())return false;
        const url=source?.mp4?.url||source?.url;let origin;
        try{origin=new URL(url);}catch{return false;}
        if(origin.protocol!=='https:'||origin.hostname!=='bjdownload.pan.wo.cn'||origin.port!=='30443')return false;
        if(diskStartupTimer!==null)return true;
        const attempt=`${roomSession.generation}:${roomSession.roomId}:${player.clientGeneration||0}:${instanceKey()}`;
        if(diskStartupAttempt===attempt)return false;
        diskStartupAttempt=attempt;
        const token=lifecycleToken,key=sourceKey(),generation=roomSession.generation,room=roomSession.roomId;
        // A download node can close the initial connection before Chromium reads metadata (code 4).
        // Retry the client connection once; a real unsupported codec still fails without a loop.
        const timer=setTimeout(()=>{
            if(diskStartupTimer!==timer)return;
            diskStartupTimer=null;
            if(!active()||token!==lifecycleToken||key!==sourceKey()||generation!==roomSession.generation
                ||room!==roomSession.roomId||video!==videoRef.value||sourceExpired()||video.readyState!==0||video.error?.code!==4)return;
            errorMessage.value='';extensionFault.value='';player.isErrorState=false;recoverableNetworkError=false;
            pendingPosition=true;loadingSource=true;playPending=false;pauseElement();
            video.src=url;video.load();reportStatus();
        },1000);
        diskStartupTimer=timer;
        clearBuffering();player.isErrorState=false;recoverableNetworkError=true;
        errorMessage.value='正在重新连接视频源…';reportStatus();return true;
    };
    const onError = () => {
        if (!active() || !hasSource() || !videoRef.value?.error || dashFailureTimer) return;
        const fault=videoRef.value.error.fault;
        if(videoRef.value.embedded&&fault?.startsWith('EXTENSION_')) {
            extensionFault.value=fault;loadingSource=false;playbackAllowed=false;clearBuffering();player.isErrorState=true;
            errorMessage.value=fault==='EXTENSION_CONNECTION_LOST'?'扩展连接已断开，恢复播放时将重新连接':'扩展播放器暂不可用，请启用扩展并重试本端播放';
            reportStatus();
            if(fault==='EXTENSION_CONNECTION_LOST'&&roomWantsPlay()&&!sourceExpired())queueMicrotask(()=>{if(alive)recoverExtension();});
            return;
        }
        const code = videoRef.value.error?.code;
        if (code === 1) return;
        extensionFault.value=sourceExpired()?'SOURCE_EXPIRED':'MEDIA_LOAD_FAILED';
        if (delivery === 'dash' && fallbackMp4(lifecycleToken)) return;
        if (retryDiskStartup()) return;
        clearBuffering(); player.isErrorState = true;
        recoverableNetworkError = code === 2;
        if (code === 3 || code === 4) errorMessage.value = '当前视频编码或视频源不可用。可尝试更换浏览器，或手动刷新视频源';
        else { errorMessage.value = '视频源或本端网络出错，可尝试刷新视频源'; refreshAfterError('MEDIA_NETWORK_ERROR'); }
        reportStatus();
    };
    const onReady = () => {
        onProgress();
        if (!active() || !videoRef.value) return;
        if (delivery === 'mp4' && videoRef.value.readyState >= 3) loadingSource = false;
        if (recoverableNetworkError && videoRef.value.readyState >= 3 && !videoRef.value.error) {
            recoverableNetworkError = false; player.isErrorState = false; errorMessage.value = '';
        }
        if (!waiting() && (player.isPaused || videoRef.value.readyState >= 3)) clearBuffering();
        align();
        if (effectivePaused()) pauseElement(); else playLocal();
        reportStatus();
    };
    const onWaiting = () => {
        if (!expectsProgress() || videoRef.value.readyState >= 3) return;
        const first = !player.isBuffering; player.isBuffering = true;
        if (first) reportStatus();
        if (stallTimer) return;
        const token = lifecycleToken;
        stallTimer = setTimeout(() => {
            stallTimer = null;
            if (!expectsProgress() || token !== lifecycleToken || !player.isBuffering || videoRef.value.readyState >= 3) return;
            bufferTimeoutError = true; errorMessage.value = '本端缓冲超时，请检查网络或刷新视频源';
            refreshAfterError('BUFFER_TIMEOUT');
        }, 8000);
    };
    const onTimeUpdate = () => {
        onProgress();
        const video = videoRef.value;
        if (!active() || !video) return;
        if (!allowsRoomAlignment()) align();
        if (!video.seeking && Number.isFinite(observedPosition) && video.currentTime > observedPosition + .01) {
            lastAdvanceAt = performance.now(); clearBuffering();
        }
        observedPosition = video.currentTime; player.localProgress = video.currentTime * 1000;
    };
    const onStalled = () => {
        if (expectsProgress() && videoRef.value.readyState < 3 && performance.now() - lastAdvanceAt > 1000) onWaiting();
    };
    const onPlaying = () => {
        if (!active() || effectivePaused() || dashFailureTimer || (loadingSource && delivery === 'dash')) { pauseElement(); if (active()) align(); return; }
        loadingSource = false;
        extensionFault.value='';
        recoverableNetworkError = false;
        clearBuffering(); player.isErrorState = false; errorMessage.value = '';
        lastAdvanceAt = performance.now(); observedPosition = videoRef.value.currentTime;
        playbackAllowed = true; qualifiedInstance = instanceKey(); needsInteraction.value = false; align();
    };
    const onPause = () => {
        if (programmaticPause) { programmaticPause = false; return; }
        if (!videoRef.value?.paused) return;
        if (pipExiting) return;
        if (pipActive) {
            clearTimeout(pipPauseTimer);
            const token = lifecycleToken;
            pipPauseTimer = setTimeout(() => {
                if (token !== lifecycleToken || !active()) return;
                if (!effectivePaused()) playLocal();
            }, 0);
            return;
        }
        if (active() && !effectivePaused() && !videoRef.value?.ended && hasSource() && !loadingSource && !player.isLoading) {
            if (document.hidden && Number(window.AndroidBridge?.getBridgeVersion?.()) >= 2) {
                if (!recoverAndroidBackgroundPause()) {
                    backgroundPolicyBlocked.value = true; playbackAllowed = false; clearBuffering(); reportStatus();
                }
            } else playLocal();
        }
    };
    const onEnterPiP = () => { pipActive = true; pipExiting = false; };
    const onLeavePiP = () => {
        pipActive = false; pipExiting = true; clearTimeout(pipPauseTimer);
        pipPauseTimer = setTimeout(() => { pipExiting = false; if (active() && !effectivePaused()) playLocal(); }, 0);
    };
    const onNativePlay = () => {
        if (!playPending && active() && (needsInteraction.value || backgroundPolicyBlocked.value)) recoverPlayback();
    };
    const onEnded = () => {
        clearBuffering(); clearTimeout(endedTimer);
        const token = lifecycleToken;
        endedTimer = setTimeout(() => { if (active() && token === lifecycleToken) player.resync(); }, 2500);
    };
    const manualRefresh = () => player.refreshVideoSource('USER_REQUEST');
    const loadSource = async () => {
        setBufferedRanges([]); bufferedSourceKey = ''; bufferedMedia = null;
        forgetAlignment?.(); forgetAlignment = null;
        if (player.nowPlaying?.playbackId && !allowsRoomAlignment()) alignmentPosition(false);
        if (recovery) recovery.aligned = false;
        loadedSourceKey = sourceKey();
        sourceAbort?.abort(); sourceAbort = new AbortController();
        lifecycleToken++; localSourceGeneration++; playPending = false; clearTimers(); pendingPosition = true;
        const token = lifecycleToken;
        observedPosition = NaN; lastAdvanceAt = performance.now(); destroyDash();
        errorMessage.value = ''; needsInteraction.value = false; recoverableNetworkError = false; player.isErrorState = false; player.isBuffering = false;
        if (qualifiedInstance !== instanceKey()) playbackAllowed = false;
        player.videoQualityOptions = []; player.actualVideoQuality = ''; player.videoDelivery = ''; delivery = ''; fallbackUsed = false;
        const video = videoRef.value;
        if (!video) return;
        const previousExtensionFault=extensionFault.value;
        video.setRetainPausedFrame?.(player.isPaused);
        pauseElement(); video.removeAttribute('src');
        if(video.embedded&&active()&&hasSource()&&video.resetSource)video.resetSource({preservePresentationOwnerId:frameOwnerId});
        else video.load();
        loadingSource = false;
        if (!active() || !hasSource()) return;
        bufferedSourceKey = bufferKey(); bufferedMedia = video;
        const source = player.nowPlaying.videoSource;
        if (video.embedded) {
            loadingSource = true; delivery = 'embedded'; player.videoDelivery = '扩展'; reportStatus();
            try {
                if(previousExtensionFault==='EXTENSION_REVOKED'&&video.connectionState==='BLOCKED')throw new DOMException('Extension revoked','NotSupportedError');
                const loaded = await video.loadEmbeddedSource(source || { url: player.nowPlaying.music?.url }, ui.videoQualityPreference, sourceKey());
                if (!alive || token !== lifecycleToken || !active()) return;
                loadingSource = false; player.videoQualityOptions = loaded.options; player.actualVideoQuality = loaded.quality;
                extensionFault.value='';align(true); if (!effectivePaused()) playLocal(); reportStatus();return true;
            } catch (error) {
                if (!alive || token !== lifecycleToken || error.name === 'AbortError') return;
                loadingSource = false; playbackAllowed = false; player.isErrorState = true;
                extensionFault.value=video.error?.fault||previousExtensionFault||'EXTENSION_UNAVAILABLE';
                errorMessage.value = '扩展播放器暂不可用，请启用扩展并重试本端播放'; reportStatus();
            }
            return false;
        }
        if (source?.dash) {
            const tracks = chooseDashTracks(source, ui.videoQualityPreference);
            if (tracks.videoTracks.length && tracks.audioTracks.length) {
                loadingSource = true; delivery = 'dash'; player.videoDelivery = 'DASH';
                player.actualVideoQuality = '正在加载'; reportStatus();
                try {
                    const probeOptions = { signal: sourceAbort.signal, fetcher: options.probeFetch || globalThis.fetch };
                    let audioDiagnostics;
                    const [audio, videos] = await Promise.all([
                        selectReachableAudio(tracks.audioCandidates, { ...probeOptions, onDiagnostics: value => { audioDiagnostics = value; } }),
                        reachableVideoTracks(tracks.videoCandidates, probeOptions)
                    ]);
                    if (!alive || token !== lifecycleToken || !active()) return;
                    if (!audio) {
                        console.warn('[VIDEO_DASH_AUDIO_PREFLIGHT]', audioDiagnostics);
                        if (fallbackMp4(token)) return;
                        loadingSource = false; recoverableNetworkError = true; player.isErrorState = true;
                        errorMessage.value = '当前没有可直连的 DASH 音轨，请刷新视频源或检查网络';
                        refreshAfterError('DASH_ERROR'); reportStatus(); return;
                    }
                    if (!videos.length) {
                        if (fallbackMp4(token)) return;
                        loadingSource = false; recoverableNetworkError = true; player.isErrorState = true;
                        errorMessage.value = '当前没有可直连的 DASH 视频轨，请刷新视频源或检查网络';
                        refreshAfterError('DASH_ERROR'); reportStatus(); return;
                    }
                    const verifiedOptions = new Map(videos.map(track => [track.qualityId, { id: track.qualityId, label: track.qualityLabel || `${track.height}p` }]));
                    player.videoQualityOptions = [...verifiedOptions.values()];
                    const selectedVideos = selectVideoQualityTracks(videos, ui.videoQualityPreference);
                    const { MediaPlayer } = options.loadDash ? await options.loadDash() : await import('dashjs');
                    if (!alive || token !== lifecycleToken || !active()) return;
                    const engine = MediaPlayer().create(); dashPlayer = engine;
                    const cdn = createDashCdnRouter([...selectedVideos, audio], () => alive && token === lifecycleToken && active());
                    engine.addRequestInterceptor(cdn.request); engine.addResponseInterceptor(cdn.response);
                    engine.updateSettings({ debug: { logLevel: 0 }, streaming: { scheduling: { scheduleWhilePaused: true },
                        buffer: { bufferTimeDefault: 12, bufferTimeAtTopQuality: 20, bufferTimeAtTopQualityLongForm: 30 },
                        retryAttempts: { MediaSegment: 3, InitializationSegment: 3, IndexSegment: 3 },
                        abr: { autoSwitchBitrate: { video: false, audio: false } } } });
                    engine.on(MediaPlayer.events.ERROR, event => {
                        if (token !== lifecycleToken || !active() || dashFailureTimer) return;
                        // dash.js emits ERROR before resolving its failed SIDX Promise. A synchronous reset
                        // can leave the remaining microtasks reading a nulled RepresentationController.
                        dashFailureTimer = setTimeout(() => {
                            if (token !== lifecycleToken || !active()) return;
                            dashFailureTimer = null;
                            if (!fallbackMp4(token)) {
                                recoverableNetworkError = [11, 17, 25, 26, 27, 28, 29].includes(event?.error?.code);
                                player.isErrorState = true; errorMessage.value = 'DASH 双轨加载失败，请检查网络或刷新视频源';
                                refreshAfterError('DASH_ERROR'); reportStatus();
                            }
                        }, 0);
                    });
                    const qualityChanged = event => {
                        if (token !== lifecycleToken || !active() || dashPlayer !== engine || event.mediaType !== 'video') return;
                        const id = event.newRepresentation?.id || event.representation?.id;
                        const index = id ? Number(String(id).split('-').at(-1)) : event.newQuality;
                        const track = selectedVideos[index];
                        if (track && (!id || id === `video-${track.id}-${index}`)) player.actualVideoQuality = track.qualityLabel || `${track.height}p`;
                    };
                    engine.on(MediaPlayer.events.QUALITY_CHANGE_RENDERED, qualityChanged);
                    engine.on(MediaPlayer.events.STREAM_INITIALIZED, () => {
                        if (token !== lifecycleToken || !active()) return;
                        loadingSource = false;
                        onReady();
                    });
                    manifestUrl = URL.createObjectURL(new Blob([createDashManifest(source.dash, selectedVideos, [audio])], { type: 'application/dash+xml' }));
                    engine.initialize(video, manifestUrl, false, targetPosition());
                    return;
                } catch {
                    if (token !== lifecycleToken || !alive) return;
                    if (fallbackMp4(token)) return;
                    loadingSource = false; player.isErrorState = true; errorMessage.value = '当前浏览器无法加载 DASH 双轨视频'; reportStatus(); return;
                }
            }
        }
        // Only the trusted muxed fallback is used; never treat a DASH video track as MP4 with sound.
        if (!fallbackMp4(token)) { player.isErrorState = true; errorMessage.value = '当前浏览器没有兼容的音视频来源'; reportStatus(); }
    };
    watch([videoRef,()=>roomSession.generation,()=>player.clientGeneration,()=>player.playbackEpoch,()=>player.modeEpoch,()=>player.nowPlaying?.playbackId,()=>player.nowPlaying?.sourceRevision],()=>{
        extensionRecovery=null;extensionRecovering.value=false;extensionAttempted='';extensionAttemptedMedia=null;
        if(videoRef.value?.connectionState!=='BLOCKED'||extensionFault.value!=='EXTENSION_REVOKED')extensionFault.value='';
    },{flush:'sync'});
    watch([videoRef, () => roomSession.generation, () => roomSession.roomId, () => player.mediaMode, () => player.clientGeneration, () => player.playbackEpoch, () => player.modeEpoch, () => player.nowPlaying?.playbackId, () => player.nowPlaying?.videoDescriptor?.page,
        () => player.nowPlaying?.sourceRevision, () => player.nowPlaying?.videoSource?.url ?? player.nowPlaying?.music?.url,
        () => ui.videoQualityPreference?.mode, () => ui.videoQualityPreference?.qualityId], loadSource, { immediate: true, flush: 'post' });
    watch(() => [player.isPaused, player.synchronized, player.videoAutoAdvance?.deadlineAt, player.videoWait?.waitId, player.videoWait?.waitRevision], () => {
        if (effectivePaused() || !active()) { pauseElement(); if (!waiting()) clearBuffering(); }
        else playLocal();
        if (waiting()) { pendingPosition = true; align(true); }
        reportStatus();
    });
    watch(() => player.playbackRate, () => { align(true); reportStatus(); });
    watch(() => roomSession.roomId, () => {
        frozenInstance = ''; frozenPosition = NaN;
        recoveryGeneration++; recovery = null; recovering.value = true;
        playbackAllowed = false; clearBuffering(); pauseElement();
        if (active()) recoverPlayback({ automaticBackground: true });
    });
    watch(() => player.synchronized, (value, previous) => {
        if (!value) { recoveryGeneration++; recovery = null; recovering.value = true; playbackAllowed = false; pauseElement(); }
        else if (previous === false) recoverPlayback({ automaticBackground: true });
    });
    watch(() => player.seekRevision, () => { pendingPosition = true; if(videoRef.value?.embedded)loadSource();else align(true); }, { flush: 'post' });
    watch(() => ui.audioVolume, value => { if (videoRef.value) videoRef.value.volume = value; }, { immediate: true });
    const foreground = event => {
        mediaPublished = '';
        reportStatus();
        if (document.hidden) return;
        backgroundPolicyBlocked.value = false;
        if (!active()) return;
        player.tryReconnect();
        const video = videoRef.value;
        if(video?.embedded&&video.connectionState!=='READY'){
            if(roomWantsPlay()&&extensionFault.value!=='EXTENSION_REVOKED')recoverExtension();
            return;
        }
        // Desktop browsers keep valid media running while minimized. A visibility
        // event alone must not pause it for a new state/clock recovery barrier.
        // Native Android transitions still require their fresh recovery contract.
        if (event?.type !== 'online' && Number(window.AndroidBridge?.getBridgeVersion?.() || 0) < 2
            && player.connected !== false && player.clockReady && !effectivePaused() && !loadingSource && !player.isLoading && !player.isErrorState
            && !needsInteraction.value && !pendingPosition && loadedSourceKey === sourceKey() && hasSource()
            && qualifiedInstance === instanceKey() && playbackAllowed && video?.readyState >= 3
            && !video.paused && !video.ended && !video.seeking) {
            reportStatus(); return;
        }
        recoverPlayback();
    };
    onMounted(() => {
        window.MusicPartyVideoControl = nativeControl;
        videoRef.value.volume = ui.audioVolume;
        document.addEventListener('visibilitychange', foreground); window.addEventListener('online', foreground);
        syncTimer = setInterval(() => {
            onProgress();
            if (active() && sourceExpired()) { extensionFault.value='SOURCE_EXPIRED';playbackAllowed = false; clearBuffering(); pauseElement(); reportStatus(); refreshAfterError('SOURCE_EXPIRED'); }
            checkRecovery();
            publishMedia();
            if (!active() || !player.nowPlaying) return;
            onTimeUpdate();
            if (!expectsProgress() && !waiting()) clearBuffering(); else onStalled();
            if ((!player.isBuffering || waiting()) && !player.isErrorState && !document.hidden) align();
            player.localProgress = videoRef.value?.currentTime * 1000 || 0;
            if (!effectivePaused() && videoRef.value?.paused && !needsInteraction.value && !errorMessage.value) playLocal();
        }, 200);
        if (player.reportVideoWatch) watchTimer = setInterval(reportStatus, 1000);
    });
    onBeforeUnmount(() => {
        setBufferedRanges([]);
        forgetAlignment?.(); forgetAlignment = null;
        if (active()) player.reportVideoWatch?.({ status: 'INACTIVE', sourceGeneration: localSourceGeneration, sequence: ++sequence,
            foreground: false, synchronizedWithRoom: false, playbackAllowed: false });
        alive = false; extensionRecovery=null; lifecycleToken++; clearTimers(); clearInterval(syncTimer); clearInterval(watchTimer); destroyDash();
        clearTimeout(pipPauseTimer);
        const released = mediaOwner.release();
        if (window.MusicPartyVideoControl === nativeControl) delete window.MusicPartyVideoControl;
        if (released) {
            window.AndroidBridge?.exitVideoFullscreen?.();
            if (Number(window.AndroidBridge?.getBridgeVersion?.()) >= 2) window.AndroidBridge.updateMedia(JSON.stringify({mediaMode:'NONE',bridgeVersion:2,bridgeGeneration,publicationSequence:nextMediaPublication(),paused:true}));
        }
        if (document.pictureInPictureElement === videoRef.value) document.exitPictureInPicture?.().catch(() => {});
        sourceAbort?.abort(); sourceAbort = null;
        document.removeEventListener('visibilitychange', foreground); window.removeEventListener('online', foreground);
        const video = videoRef.value;
        if (video) { video.pause(); video.removeAttribute('src'); video.load(); }
        player.localProgress = 0; player.isBuffering = false; player.isErrorState = false;
        // Keep the last trusted/compatible quality labels so music mode can save a video preference.
        player.actualVideoQuality = ''; player.videoDelivery = '';
    });
    return { needsInteraction, errorMessage, recovering, faultType:extensionFault, extensionFault, extensionRecovering, recoverExtension, backgroundPolicyBlocked, recoverPlayback, playLocal, manualRefresh, onEnterPiP, onLeavePiP, onNativePlay, onReady, onWaiting, onStalled, onProgress, onTimeUpdate, onPlaying, onPause, onEnded, onError, reportStatus };
}
