// src/composables/useAudio.js

import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import { socketService } from '../services/socket';
import { WS_DEST } from '../constants/api';
import { claimMediaOwner, clearMediaActions, nextMediaPublication } from '../utils/mediaOwner.js';
import { roomSession } from '../services/roomSession.js';
import { useGuideStore } from '../stores/guide.js';

// 阈值常量
const ENDED_RESYNC_DELAY = 2500;       // 当前曲播完但服务器未推新曲时，等待多久后主动重同步
const STALL_TIMEOUT = 8000;            // 持续缓冲超时（ms），超时后重载 + 重同步
const POSITION_STATE_INTERVAL = 1000;  // setPositionState 节流间隔（ms）
const SHELL_NOTIFY_INTERVAL = 15000;   // 安卓原生壳通知栏进度刷新间隔（ms）

export function useAudio(audioRef, playerStore) {
    const guide = useGuideStore();
    const bridgeGeneration = Number(globalThis.window?.AndroidBridge?.getBridgeGeneration?.() || 0);
    const ownerToken = `music-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    let nativeSequence = -1;
    const nativeContext = () => ({bridgeVersion:2,bridgeGeneration,roomId:roomSession.roomId,mediaMode:'MUSIC',
        clientGeneration:playerStore.clientGeneration || 0,playbackEpoch:playerStore.playbackEpoch,modeEpoch:playerStore.modeEpoch || 0,
        playbackId:playerStore.nowPlaying?.playbackId,rendererGeneration:1,ownerToken});
    let alive = true, lifecycleToken = 0;
    const retryTimers = new Set();
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const retryCount = ref(0);
    const isErrorState = ref(false);
    let syncTimer = null;
    let wakeLock = null;
    let endedTimer = null;
    let endedTrackId = null;
    let stallTimer = null;
    let lastPositionStateAt = 0;
    let lastShellNotifyAt = 0;
    let pendingServerPosition = false;
    const mediaOwner = claimMediaOwner(() => {
        lifecycleToken++;
        retryTimers.forEach(clearTimeout); retryTimers.clear();
        clearInterval(syncTimer); clearTimeout(endedTimer); clearTimeout(stallTimer);
        audioRef.value?.pause(); clearMediaActions();
        releaseWakeLock();
    });
    const ownsPlayback = () => alive && mediaOwner.current() && playerStore.mediaMode === 'MUSIC';

    // 请求唤醒锁 (防止 WebSocket 断连)
    const requestWakeLock = async () => {
        if (ownsPlayback() && 'wakeLock' in navigator) {
            try {
                const token = lifecycleToken;
                const acquired = await navigator.wakeLock.request('screen');
                if (!ownsPlayback() || token !== lifecycleToken) { await acquired.release(); return; }
                wakeLock = acquired;
                console.log('Wake Lock active');
            } catch (err) {
                console.warn('Wake Lock request failed:', err);
            }
        }
    };

    // 释放唤醒锁
    const releaseWakeLock = async () => {
        if (wakeLock !== null) {
            const released = wakeLock;
            wakeLock = null;
            await released.release();
        }
    };

    // 更新系统媒体中心 (锁屏控制)
    const updateMediaSession = () => {
        if (!ownsPlayback() || !('mediaSession' in navigator) || !playerStore.nowPlaying) return;

        const music = playerStore.nowPlaying.music;
        const context = nativeContext();
        const valid = () => alive && mediaOwner.current() && playerStore.mediaMode === 'MUSIC'
            && Object.keys(context).every(key => nativeContext()[key] === context[key]);

        // 1. 设置元数据
        navigator.mediaSession.metadata = new MediaMetadata({
            title: music.name,
            artist: music.artists.join(' / '),
            artwork: [
                { src: music.coverUrl, sizes: '512x512', type: 'image/png' }
            ]
        });

        // 2. 注册控制事件 (关键：告诉系统我们支持后台控制)
        try {
            navigator.mediaSession.setActionHandler('play', () => { if (valid() && !guide.active) playerStore.togglePause(); });
            navigator.mediaSession.setActionHandler('pause', () => { if (valid() && !guide.active) playerStore.togglePause(); });
            navigator.mediaSession.setActionHandler('previoustrack', null); // 暂不支持上一首
            navigator.mediaSession.setActionHandler('nexttrack', () => { if (valid() && !guide.active) playerStore.playNext(); });
        } catch (e) {
            console.warn('Media Session actions warning:', e);
        }
    };

    // 安卓原生壳：把播放状态推给通知栏媒体控制器（window.AndroidBridge 由原生注入）
    const notifyAndroidShell = () => {
        const bridge = typeof window !== 'undefined' ? window.AndroidBridge : null;
        if (!ownsPlayback() || !bridge?.updateMedia || !playerStore.nowPlaying) return;
        const music = playerStore.nowPlaying.music;
        bridge.updateMedia(JSON.stringify({
            bridgeVersion: 2, bridgeGeneration, publicationSequence: nextMediaPublication(), mediaMode: 'MUSIC',
            context: nativeContext(),
            title: music.name,
            artist: music.artists.join(' / '),
            position: Math.round(playerStore.localProgress / 1000),
            duration: Math.round((music.duration || 0) / 1000),
            paused: playerStore.isPaused,
            coverUrl: music.coverUrl || '',
        }));
    };

    // Apply every acknowledged seek, even when the jump is below the normal drift threshold.
    const applyServerPosition = () => {
        const audio=audioRef.value;
        if (!ownsPlayback() || !pendingServerPosition || !audio || audio.readyState<1 || !playerStore.nowPlaying) return;
        const duration=Number.isFinite(audio.duration)?audio.duration:playerStore.nowPlaying.music.duration/1000;
        const position=Math.max(0,Math.min(playerStore.getCurrentProgress()/1000,Math.max(0,duration-.001)));
        try { audio.currentTime=position; } catch { return; }
        pendingServerPosition=false;
        localProgress.value=position*1000;
        playerStore.localProgress=localProgress.value;
        if (playerStore.isPaused) audio.pause();
        if ('mediaSession' in navigator && navigator.mediaSession.setPositionState && duration>0) {
            try { navigator.mediaSession.setPositionState({duration,position,playbackRate:1}); } catch {}
        }
        lastPositionStateAt=Date.now();
        notifyAndroidShell();
    };
    watch(() => playerStore.seekRevision, () => {
        if (!ownsPlayback()) return;
        pendingServerPosition=true;
        applyServerPosition();
    }, {flush:'post'});

    // 尝试播放并处理浏览器拦截
    const safePlay = async () => {
        if (!ownsPlayback() || !audioRef.value || !playerStore.nowPlaying || playerStore.isPaused) return;
        const audio = audioRef.value, token = lifecycleToken;

        try {
            await audio.play();
            if (!ownsPlayback() || token !== lifecycleToken || playerStore.isPaused) { audio.pause(); return; }
            isErrorState.value = false;
            updateMediaSession();
            requestWakeLock();
        } catch (e) {
            if (!ownsPlayback() || token !== lifecycleToken) return;
            if (e.name === 'NotAllowedError') {
                console.warn("Autoplay blocked. User interaction required.");
            } else if (e.name !== 'AbortError') {
                console.warn("Play failed:", e);
            }
        }
    };

    // === 1. 监听资源加载 (canplay) ===
    const checkAutoPlay = () => {
        if (!ownsPlayback() || !audioRef.value || !playerStore.nowPlaying) return;
        applyServerPosition();
        isBuffering.value = false;
        clearTimeout(endedTimer);   // 新曲已到位，取消 ended 重同步等待
        clearTimeout(stallTimer);

        if (playerStore.isPaused) {
            audioRef.value.pause();
        } else {
            safePlay();
        }
    };

    // === 2. 监听后端状态变化 ===
    watch(() => playerStore.isPaused, (newPaused) => {
        if (!ownsPlayback() || !audioRef.value) return;
        if (newPaused) {
            audioRef.value.pause();
            if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
            releaseWakeLock();
        } else {
            safePlay();
            if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'playing';
        }
        notifyAndroidShell();
    });

    // === 3. 监听切歌 ===
    watch(() => playerStore.nowPlaying?.playbackId, () => {
        if (!ownsPlayback()) return;
        // 服务器已推新曲（或清空），取消 ended 重同步等待
        clearTimeout(endedTimer);
        endedTrackId = null;

        // 更新媒体中心信息 (锁屏显示)
        if ('mediaSession' in navigator && playerStore.nowPlaying) {
            const music = playerStore.nowPlaying.music;
            navigator.mediaSession.metadata = new MediaMetadata({
                title: music.name,
                artist: music.artists.join(' / '),
                artwork: [{ src: music.coverUrl, sizes: '512x512', type: 'image/png' }]
            });
        }

        retryCount.value = 0;
        isErrorState.value = false;
        updateMediaSession();
        notifyAndroidShell();
    });

    // === 4. 错误重试机制 ===
    const handleError = () => {
        if (!ownsPlayback() || !playerStore.nowPlaying?.music?.url) return;

        if (audioRef.value && audioRef.value.error && audioRef.value.error.code === 20) return;

        isBuffering.value = false;
        if (retryCount.value >= 3) {
            isErrorState.value = true;
            return;
        }

        retryCount.value++;
        console.log(`Retry audio (${retryCount.value})...`);
        const token = lifecycleToken;
        const timer = setTimeout(() => {
            retryTimers.delete(timer);
            if (ownsPlayback() && token === lifecycleToken && audioRef.value) {
                audioRef.value.load();
            }
        }, 1500);
        retryTimers.add(timer);
    };

    // === 5. 音频自然结束兜底：服务器没推新曲就主动重同步 ===
    const handleEnded = () => {
        if (!ownsPlayback() || playerStore.isPaused) return; // 服务器暂停态：保持暂停，不动作

        endedTrackId = playerStore.nowPlaying?.music?.id ?? null;
        clearTimeout(endedTimer);
        endedTimer = setTimeout(() => {
            if (!ownsPlayback()) return;
            const currentId = playerStore.nowPlaying?.music?.id ?? null;
            // 超时仍停留在同一曲（或无曲目）→ 服务器广播丢失/连接异常
            if (currentId === endedTrackId) {
                console.warn('[Ended] No next track pushed, forcing resync...');
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, ENDED_RESYNC_DELAY);
    };

    // === 6. 持续缓冲看门狗：不再无限干等 ===
    const onWaiting = () => {
        if (!ownsPlayback()) return;
        isBuffering.value = true;
        clearTimeout(stallTimer);
        stallTimer = setTimeout(() => {
            if (ownsPlayback() && audioRef.value && !playerStore.isPaused) {
                console.warn('[Stall] Buffer timeout, reloading audio...');
                audioRef.value.load();
                playerStore.tryReconnect();
                socketService.send(WS_DEST.RESYNC);
            }
        }, STALL_TIMEOUT);
    };

    const onPlaying = () => {
        if (!ownsPlayback()) return;
        isBuffering.value = false;
        clearTimeout(stallTimer);
    };

    // 页面可见性变化监听
    const handleVisibilityChange = async () => {
        if (!ownsPlayback()) return;
        if (document.visibilityState === 'visible') {
            if (!playerStore.isPaused) {
                await requestWakeLock();
            }
            if (!ownsPlayback()) return;
            playerStore.tryReconnect();
            // 回到前台无条件重同步，立即对齐服务器进度
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // 网络状态监听
    const handleNetworkChange = () => {
        if (ownsPlayback() && navigator.onLine) {
            console.log('[Network] Back online, checking socket...');
            playerStore.tryReconnect();
            socketService.send(WS_DEST.RESYNC);
        }
    };

    // === 7. 进度条同步 ===
    onMounted(() => {
        if (!ownsPlayback()) return;
        document.addEventListener('visibilitychange', handleVisibilityChange);
        window.addEventListener('online', handleNetworkChange);

        // 注册通知栏媒体控制的 Web 端动作（由原生 evaluateJavascript 调用）
        if (typeof window !== 'undefined') {
            window.MusicPartyControl = {
                handle: raw => {
                    let envelope;try {envelope=JSON.parse(raw);}catch{return;}
                    const current=nativeContext();
                    if(!alive || !mediaOwner.current() || playerStore.mediaMode!=='MUSIC'
                        || !Object.keys(current).every(key=>current[key]===envelope[key])
                        || !Number.isSafeInteger(envelope.actionSequence) || envelope.actionSequence<=nativeSequence) return;
                    nativeSequence=envelope.actionSequence;
                    if (guide.active) return;
                    if(envelope.action==='play' || envelope.action==='pause') playerStore.togglePause();
                    else if(envelope.action==='next') playerStore.playNext();
                },
                togglePause: () => { if (alive && mediaOwner.current() && playerStore.mediaMode === 'MUSIC' && !guide.active) playerStore.togglePause(); },
                next: () => { if (alive && mediaOwner.current() && playerStore.mediaMode === 'MUSIC' && !guide.active) playerStore.playNext(); },
            };
        }

        syncTimer = setInterval(() => {
            if (!ownsPlayback()) return;
            if (!playerStore.nowPlaying) {
                localProgress.value = 0;
                return;
            }

            // 1. 获取理论上的正确进度
            const targetTime = playerStore.getCurrentProgress();

            // 2. 更新 UI 绑定值 (localProgress)
            if (audioRef.value && !audioRef.value.paused) {
                localProgress.value = audioRef.value.currentTime * 1000;
            } else {
                localProgress.value = targetTime;
            }

            // 3. 强行同步逻辑 (纠偏)
            if (audioRef.value && !isBuffering.value && !isErrorState.value) {
                if (playerStore.isPaused) {
                    if (Math.abs(audioRef.value.currentTime * 1000 - targetTime) > 200) {
                        audioRef.value.currentTime = targetTime / 1000;
                    }
                } else {
                    const domTime = audioRef.value.currentTime * 1000;
                    const threshold = document.hidden ? 10000 : 2000;
                    if (Math.abs(domTime - targetTime) > threshold) {
                        if (audioRef.value.readyState >= 2) {
                            console.log(`[Sync] Correcting time (${document.hidden ? 'bg' : 'fg'}): ${domTime} -> ${targetTime}`);
                            audioRef.value.currentTime = targetTime / 1000;
                        }
                    }
                }
            }

            // 4. 锁屏/通知栏真实进度（节流，避免每 200ms 都调用）
            const now = Date.now();
            if (now - lastPositionStateAt >= POSITION_STATE_INTERVAL
                && 'mediaSession' in navigator && navigator.mediaSession.setPositionState) {
                lastPositionStateAt = now;
                try {
                    navigator.mediaSession.setPositionState({
                        duration: playerStore.nowPlaying.music.duration / 1000,
                        playbackRate: 1,
                        position: playerStore.localProgress / 1000,
                    });
                } catch (e) {
                    // 某些状态（如无有效时长）会抛异常，忽略
                }
            }

            // 5. 安卓原生壳：定期刷新通知栏进度（WebView 无原生 MediaSession，靠轮询补偿）
            if (now - lastShellNotifyAt >= SHELL_NOTIFY_INTERVAL) {
                lastShellNotifyAt = now;
                notifyAndroidShell();
            }
        }, 200);
    });

    onBeforeUnmount(() => {
        alive = false; lifecycleToken++;
        retryTimers.forEach(clearTimeout); retryTimers.clear();
        if (audioRef.value) { audioRef.value.pause(); audioRef.value.removeAttribute('src'); audioRef.value.load(); }
        if (mediaOwner.current() && typeof window !== 'undefined') {
            delete window.MusicPartyControl;
            window.AndroidBridge?.updateMedia?.(JSON.stringify({bridgeVersion:2,bridgeGeneration,publicationSequence:nextMediaPublication(),mediaMode:'NONE',title:'MirthParty',artist:'',paused:true,position:0,duration:0,coverUrl:''}));
        }
        if (mediaOwner.release() && 'mediaSession' in navigator) {
            navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none';
            for (const action of ['play', 'pause', 'previoustrack', 'nexttrack', 'seekto', 'seekforward', 'seekbackward']) {
                try { navigator.mediaSession.setActionHandler(action, null); } catch {}
            }
            try { navigator.mediaSession.setPositionState?.(); } catch {}
        }
        document.removeEventListener('visibilitychange', handleVisibilityChange);
        window.removeEventListener('online', handleNetworkChange);
        clearInterval(syncTimer);
        clearTimeout(endedTimer);
        clearTimeout(stallTimer);
        releaseWakeLock();
    });

    return {
        localProgress,
        isBuffering,
        isErrorState,
        retryCount,
        handleError,
        applyServerPosition,
        checkAutoPlay,
        handleEnded,
        onWaiting,
        onPlaying
    };
}
