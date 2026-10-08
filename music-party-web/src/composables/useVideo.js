import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { chooseDashTracks, createDashManifest, playableAt } from '../utils/videoManifest.js';
import { selectReachableAudio, reachableVideoTracks, createDashCdnRouter } from '../utils/videoCdn.js';

// One element follows the room; native events never send playback controls.
export function useVideo(videoRef, player, ui, options = {}) {
    const needsInteraction = ref(false), errorMessage = ref('');
    let lifecycleToken = 0, alive = true, pendingPosition = true, playPending = false;
    let syncTimer, watchTimer, stallTimer, endedTimer, refreshedSource = '', programmaticPause = false;
    let observedPosition = NaN, lastAdvanceAt = 0, bufferTimeoutError = false;
    let dashPlayer = null, manifestUrl = null, localSourceGeneration = 0, sequence = 0;
    let playbackAllowed = false, qualifiedInstance = '', delivery = '', loadingSource = false, fallbackUsed = false;
    let recoverableNetworkError = false;
    let sourceAbort = null;
    let dashFailureTimer = null;
    const sourceKey = () => `${player.clientGeneration || 0}:${player.playbackEpoch}:${player.nowPlaying?.playbackId}:${player.nowPlaying?.sourceRevision || 0}`;
    const instanceKey = () => `${player.playbackEpoch}:${player.modeEpoch}:${player.nowPlaying?.playbackId}`;
    const active = () => alive && player.mediaMode === 'VIDEO' && player.synchronized;
    const waiting = () => !!player.videoWait;
    const effectivePaused = () => player.isPaused || waiting();
    const rate = () => player.playbackRate || 1;
    const roomWantsPlay = () => player.playIntent ? player.playIntent === 'PLAYING' : !player.isPaused;
    const hasSource = () => !!(player.nowPlaying?.videoSource?.dash || player.nowPlaying?.videoSource?.url || player.nowPlaying?.music?.url);
    const targetPosition = () => Math.max(0, waiting() ? player.videoWait.frozenPosition / 1000 : player.getCurrentProgress() / 1000);
    const clearBuffering = () => {
        clearTimeout(stallTimer); stallTimer = null; player.isBuffering = false;
        if (bufferTimeoutError) { errorMessage.value = ''; bufferTimeoutError = false; }
    };
    const clearTimers = () => { clearBuffering(); clearTimeout(endedTimer); clearTimeout(dashFailureTimer); dashFailureTimer = null; };
    const expectsProgress = () => active() && !effectivePaused() && !videoRef.value?.paused
        && !videoRef.value?.ended && !needsInteraction.value && !player.isErrorState;
    const pauseElement = () => { programmaticPause = true; videoRef.value?.pause(); };
    const align = (immediate = false) => {
        const video = videoRef.value;
        if (!active() || !video || !player.nowPlaying || video.readyState < 1) { pendingPosition = true; return; }
        if (video.seeking && !pendingPosition) return;
        let target = targetPosition();
        const duration = Number.isFinite(video.duration) ? video.duration : player.nowPlaying.music.duration / 1000;
        if (duration > 0) target = Math.min(target, Math.max(0, duration - .001));
        if (video.seekable.length) {
            let nearest = null;
            for (let i = 0; i < video.seekable.length; i++) {
                const start = video.seekable.start(i), end = Math.max(start, video.seekable.end(i) - .001);
                const candidate = Math.max(start, Math.min(end, target));
                if (nearest === null || Math.abs(candidate - target) < Math.abs(nearest - target)) nearest = candidate;
            }
            target = nearest;
        }
        const threshold = pendingPosition ? .001 : immediate ? .05 : effectivePaused() ? .2 : .5;
        const needsSeek = Math.abs(video.currentTime - target) > threshold;
        pendingPosition = false;
        if (needsSeek) {
            observedPosition = target;
            try { video.currentTime = target; } catch { pendingPosition = true; }
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
        else if (needsInteraction.value || !playbackAllowed) status = 'BLOCKED';
        else if (player.isErrorState && !recoverableNetworkError) status = 'ERROR';
        else if (playableAt(video, waiting() ? targetPosition() : video.currentTime, rate(), dashPlayer)) status = 'READY';
        else if (roomWantsPlay() && (player.isBuffering || waiting() || loadingSource || recoverableNetworkError)) status = 'BUFFERING';
        player.reportVideoWatch({ status, sourceGeneration: localSourceGeneration, sequence: ++sequence,
            synchronizedWithRoom: player.synchronized && !!video && (qualifiedInstance === instanceKey()
                || (!pendingPosition && Math.abs(video.currentTime - targetPosition()) <= .75)),
            foreground, playbackAllowed, positionMs: Math.round((video?.currentTime || 0) * 1000),
            waitId: player.videoWait?.waitId ?? null, waitRevision: player.videoWait?.waitRevision ?? null });
    };
    const playLocal = async () => {
        const video = videoRef.value;
        if (!active() || !video || effectivePaused() || !hasSource() || playPending || dashFailureTimer || (loadingSource && delivery === 'dash') || errorMessage.value) return;
        if (!video.paused) { align(); return; }
        const token = lifecycleToken;
        playPending = true; align(true);
        try {
            await video.play();
            if (!alive) { video.pause(); return; }
            if (token !== lifecycleToken) return;
            if (effectivePaused() || !active()) { pauseElement(); return; }
            needsInteraction.value = false; playbackAllowed = true; qualifiedInstance = instanceKey();
            programmaticPause = false; align(); reportStatus();
        } catch (error) {
            if (!alive || token !== lifecycleToken) return;
            if (error.name === 'NotAllowedError') { needsInteraction.value = true; playbackAllowed = false; clearBuffering(); reportStatus(); }
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
        destroyDash(); delivery = 'mp4'; player.videoDelivery = 'MP4';
        player.actualVideoQuality = source?.mp4?.qualityLabel || (source?.quality ? `MP4 · ${source.quality}` : 'MP4');
        player.videoQualityOptions = [];
        pendingPosition = true; loadingSource = true; recoverableNetworkError = false; errorMessage.value = ''; player.isErrorState = false;
        pauseElement(); videoRef.value.src = url; videoRef.value.load(); reportStatus();
        return true;
    };
    const onError = () => {
        if (!active() || !hasSource() || !videoRef.value?.error || dashFailureTimer) return;
        const code = videoRef.value.error?.code;
        if (code === 1) return;
        if (delivery === 'dash' && fallbackMp4(lifecycleToken)) return;
        clearBuffering(); player.isErrorState = true;
        recoverableNetworkError = code === 2;
        if (code === 3 || code === 4) errorMessage.value = '当前视频编码或视频源不可用。可尝试更换浏览器，或手动刷新视频源';
        else { errorMessage.value = '视频源或本端网络出错，可尝试刷新视频源'; refreshAfterError('MEDIA_NETWORK_ERROR'); }
        reportStatus();
    };
    const onReady = () => {
        if (!active() || !videoRef.value) return;
        if (delivery === 'mp4' && videoRef.value.readyState >= 3) loadingSource = false;
        if (recoverableNetworkError && videoRef.value.readyState >= 3 && !videoRef.value.error) {
            recoverableNetworkError = false; player.isErrorState = false; errorMessage.value = '';
        }
        if (!waiting() && (player.isPaused || videoRef.value.readyState >= 3)) clearBuffering();
        align();
        if (effectivePaused()) pauseElement(); else playLocal();
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
        const video = videoRef.value;
        if (!active() || !video) return;
        if (!video.seeking && Number.isFinite(observedPosition) && video.currentTime > observedPosition + .01) {
            lastAdvanceAt = performance.now(); clearBuffering();
        }
        observedPosition = video.currentTime; player.localProgress = video.currentTime * 1000;
    };
    const onStalled = () => {
        if (expectsProgress() && videoRef.value.readyState < 3 && performance.now() - lastAdvanceAt > 1000) onWaiting();
    };
    const onPlaying = () => {
        if (!active() || effectivePaused() || dashFailureTimer || (loadingSource && delivery === 'dash')) { pauseElement(); return; }
        loadingSource = false;
        recoverableNetworkError = false;
        clearBuffering(); player.isErrorState = false; errorMessage.value = '';
        lastAdvanceAt = performance.now(); observedPosition = videoRef.value.currentTime;
        playbackAllowed = true; qualifiedInstance = instanceKey(); needsInteraction.value = false; align();
    };
    const onPause = () => {
        if (programmaticPause) { programmaticPause = false; return; }
        if (active() && !effectivePaused() && !videoRef.value?.ended && hasSource() && !loadingSource && !player.isLoading) {
            needsInteraction.value = true; playbackAllowed = false; reportStatus();
        }
    };
    const onEnded = () => {
        clearBuffering(); clearTimeout(endedTimer);
        const token = lifecycleToken;
        endedTimer = setTimeout(() => { if (active() && token === lifecycleToken) player.resync(); }, 2500);
    };
    const manualRefresh = () => player.refreshVideoSource('USER_REQUEST');
    const loadSource = async () => {
        sourceAbort?.abort(); sourceAbort = new AbortController();
        lifecycleToken++; localSourceGeneration++; playPending = false; clearTimers(); pendingPosition = true;
        const token = lifecycleToken;
        observedPosition = NaN; lastAdvanceAt = performance.now(); destroyDash();
        errorMessage.value = ''; needsInteraction.value = false; recoverableNetworkError = false; player.isErrorState = false; player.isBuffering = false;
        if (qualifiedInstance !== instanceKey()) playbackAllowed = false;
        player.videoQualityOptions = []; player.actualVideoQuality = ''; player.videoDelivery = ''; delivery = ''; fallbackUsed = false;
        const video = videoRef.value;
        if (!video) return;
        pauseElement(); video.removeAttribute('src'); video.load();
        loadingSource = false;
        if (!active() || !hasSource()) return;
        const source = player.nowPlaying.videoSource;
        if (source?.dash) {
            const tracks = chooseDashTracks(source, ui.videoQualityPreference);
            const unique = new Map(tracks.available.map(track => [track.qualityId, { id: track.qualityId, label: track.qualityLabel || `${track.height}p` }]));
            player.videoQualityOptions = [...unique.values()];
            if (tracks.videoTracks.length && tracks.audioTracks.length) {
                loadingSource = true; delivery = 'dash'; player.videoDelivery = 'DASH';
                player.actualVideoQuality = '正在加载'; reportStatus();
                try {
                    const probeOptions = { signal: sourceAbort.signal, fetcher: options.probeFetch || globalThis.fetch };
                    const [audio, videos] = await Promise.all([
                        selectReachableAudio(tracks.audioCandidates, probeOptions),
                        reachableVideoTracks(tracks.videoCandidates, probeOptions)
                    ]);
                    if (!alive || token !== lifecycleToken || !active()) return;
                    if (!audio) {
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
                    let selectedVideos = videos;
                    if (ui.videoQualityPreference?.mode === 'manual') {
                        const preference = ui.videoQualityPreference.qualityId;
                        const desired = videos.find(track => track.qualityId === preference);
                        const lower = videos.filter(track => track.quality <= Number(preference?.slice(1)));
                        const selectedQuality = desired?.quality ?? lower.at(-1)?.quality ?? videos[0].quality;
                        selectedVideos = videos.filter(track => track.quality === selectedQuality);
                    }
                    const { MediaPlayer } = options.loadDash ? await options.loadDash() : await import('dashjs');
                    if (!alive || token !== lifecycleToken || !active()) return;
                    const engine = MediaPlayer().create(); dashPlayer = engine;
                    const cdn = createDashCdnRouter([...selectedVideos, audio], () => alive && token === lifecycleToken && active());
                    engine.addRequestInterceptor(cdn.request); engine.addResponseInterceptor(cdn.response);
                    engine.updateSettings({ debug: { logLevel: 0 }, streaming: { scheduling: { scheduleWhilePaused: true },
                        buffer: { bufferTimeDefault: 12, bufferTimeAtTopQuality: 20, bufferTimeAtTopQualityLongForm: 30 },
                        retryAttempts: { MediaSegment: 3, InitializationSegment: 3, IndexSegment: 3 },
                        abr: { autoSwitchBitrate: { video: ui.videoQualityPreference?.mode !== 'manual', audio: false } } } });
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
                        if (token !== lifecycleToken || event.mediaType !== 'video') return;
                        const id = event.newRepresentation?.id || event.representation?.id;
                        const index = id ? Number(String(id).split('-').at(-1)) : event.newQuality;
                        const track = selectedVideos[index];
                        if (track) player.actualVideoQuality = track.qualityLabel || `${track.height}p`;
                    };
                    engine.on(MediaPlayer.events.QUALITY_CHANGE_RENDERED, qualityChanged);
                    engine.on(MediaPlayer.events.STREAM_INITIALIZED, () => {
                        if (token !== lifecycleToken || !active()) return;
                        loadingSource = false;
                        const track = selectedVideos[0];
                        if (track) player.actualVideoQuality = track.qualityLabel || `${track.height}p`;
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
    watch([videoRef, () => player.clientGeneration, () => player.playbackEpoch, () => player.modeEpoch, () => player.nowPlaying?.playbackId,
        () => player.nowPlaying?.sourceRevision, () => player.nowPlaying?.videoSource?.url ?? player.nowPlaying?.music?.url,
        () => ui.videoQualityPreference?.mode, () => ui.videoQualityPreference?.qualityId], loadSource, { immediate: true, flush: 'post' });
    watch(() => [player.isPaused, player.synchronized, player.videoWait?.waitId, player.videoWait?.waitRevision], () => {
        if (effectivePaused() || !active()) { pauseElement(); if (!waiting()) clearBuffering(); }
        else playLocal();
        if (waiting()) { pendingPosition = true; align(true); }
        reportStatus();
    });
    watch(() => player.playbackRate, () => { align(true); reportStatus(); });
    watch(() => player.seekRevision, () => { pendingPosition = true; align(true); }, { flush: 'post' });
    watch(() => ui.audioVolume, value => { if (videoRef.value) videoRef.value.volume = value; }, { immediate: true });
    const foreground = () => {
        reportStatus();
        if (document.hidden || !active()) return;
        player.tryReconnect(); player.sampleClock(); player.resync(); align(true);
        if (!effectivePaused()) playLocal();
    };
    onMounted(() => {
        videoRef.value.volume = ui.audioVolume;
        document.addEventListener('visibilitychange', foreground); window.addEventListener('online', foreground);
        syncTimer = setInterval(() => {
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
        if (active()) player.reportVideoWatch?.({ status: 'INACTIVE', sourceGeneration: localSourceGeneration, sequence: ++sequence,
            foreground: false, synchronizedWithRoom: false, playbackAllowed: false });
        alive = false; lifecycleToken++; clearTimers(); clearInterval(syncTimer); clearInterval(watchTimer); destroyDash();
        sourceAbort?.abort(); sourceAbort = null;
        document.removeEventListener('visibilitychange', foreground); window.removeEventListener('online', foreground);
        const video = videoRef.value;
        if (video) { video.pause(); video.removeAttribute('src'); video.load(); }
        player.localProgress = 0; player.isBuffering = false; player.isErrorState = false;
        // Keep the last trusted/compatible quality labels so music mode can save a video preference.
        player.actualVideoQuality = ''; player.videoDelivery = '';
    });
    return { needsInteraction, errorMessage, playLocal, manualRefresh, onReady, onWaiting, onStalled, onTimeUpdate, onPlaying, onPause, onEnded, onError, reportStatus };
}
