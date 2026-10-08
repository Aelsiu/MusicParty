// src/stores/player.js

import { roomSession } from '../services/roomSession';
import { defineStore } from 'pinia';
import { ref, computed, watch } from 'vue';
import { useUserStore } from './user';
import { socketService } from '../services/socket';
import { createSocketSubscriptions, createSocketCallbacks } from '../services/socketHandler'; // 引入新文件
import { musicApi } from '../api/music';
import { WS_DEST } from '../constants/api';
import { STORAGE_KEYS } from '../constants/keys';
import { createPlaybackBarrier, createServerClock, createPlaybackRequestId } from '../utils/playbackProtocol';

export const usePlayerStore = defineStore('player', () => {
    // === 1. State ===
    const nowPlaying = ref(null);
    const mediaMode = ref('MUSIC');
    const playbackEpoch = ref(null), modeEpoch = ref(0), stateRevision = ref(-1), queueRevision = ref(-1);
    const clientGeneration = ref(0), synchronized = ref(false), upgradeRequired = ref(false);
    const videoFeatures = ref({}), sourceErrorCode = ref(null), policyRecoveryRequired = ref(false), videoPending = ref(null), videoHistoryRevision = ref(0), interaction = ref(null), videoImports = ref({});
    let pendingInteraction = null;
    const sourceStatus = ref('EMPTY'), sourceRetryAt = ref(null), clockReady = ref(false);
    const barrier = createPlaybackBarrier(), serverClock = createServerClock();
    const clockRequests = new Map();
    let clockTimers = [], progressAnchor = 0;
    const queue = ref([]);
    const isPaused = ref(false);
    const playIntent = ref('PAUSED'), playbackRate = ref(1), videoWait = ref(null);
    const intentPaused = computed(() => mediaMode.value === 'VIDEO' ? playIntent.value === 'PAUSED' : isPaused.value);
    const videoQualityOptions = ref([]), actualVideoQuality = ref(''), videoDelivery = ref('');
    const playMode = ref('SEQUENTIAL'); // 'SEQUENTIAL' | 'SHUFFLE' | 'REPEAT_ONE'
    const isShuffle = computed(() => playMode.value === 'SHUFFLE');
    const isRepeatOne = computed(() => playMode.value === 'REPEAT_ONE');
    const isFairShuffle = ref(true);
    const allowOfflineShuffle = ref(false);
    const isPauseLocked = ref(false);
    const isSkipLocked = ref(false);
    const isPlayModeLocked = ref(false);
    const lyricText = ref('');
    const chorusMarkers = ref([]);
    const seekDeadline = ref(0);
    const seekRevision = ref(0);
    let pendingSeek = 0;
    const connected = ref(false);
    const isLoading = ref(false);
    const streamListenerCount = ref(0);
    const streamActive = ref(false);
    const lastControlTime = ref(0);
    const remotePosition = ref(0);
    const lastSyncTime = ref(0);
    const localProgress = ref(0);
    const isBuffering = ref(false);
    const isErrorState = ref(false);

    // 投票切歌相关
    const isVoteSkipEnabled = ref(false);
    const voteSkipThreshold = ref(0.5);
    const voteSkipWaitTime = ref(15);
    const currentVotes = ref(0);
    const eligibleUsers = ref(0);

    const config = ref({
        maxQueueSize: 1000,
        maxHistorySize: 50,
        maxUserSongs: 100,
        maxPlaylistImportSize: 100,
        maxChatHistorySize: 1000,
        minChatIntervalMs: 1000,
        maxChatMessageLength: 200,
        neteaseQuality: 'exhigh',
        neteaseEnabled: true,
        bilibiliEnabled: true,
        videoMaxDurationMinutes: 120, videoMaxResolution: 'UNLIMITED', videoPolicyRevision: 0,
        bilibiliCookieConfigured: false,
        voteSkipEnabled: false,
        voteSkipThreshold: 0.5,
        voteSkipWaitTime: 15,
        neteaseCookieConfigured: false,
        seekPolicy: 'DISABLED',
        privateDj: {
            mode: 'OFF',
            fillBlankEnabled: false,
            joinQueueEnabled: false,
            custodyEnabled: false
        }
    });

    const userStore = useUserStore();
    const LOCAL_COOLDOWN = 500; // 稍微调低一点冷却时间提升手感
    const canSeek = computed(() => connected.value && synchronized.value && !userStore.isGuest && !isLoading.value
        && nowPlaying.value?.playbackId && nowPlaying.value.music.duration > 0
        && (config.value.seekPolicy === 'ALL' || (config.value.seekPolicy === 'OWNER_AND_ENQUEUER'
            && (roomSession.ownerAccess || nowPlaying.value.enqueuedById === userStore.userToken))));

    // === 2. Logic ===
    const getCurrentProgress = () => {
        if (!nowPlaying.value) return 0;
        if (isPaused.value) {
            return remotePosition.value;
        } else {
            // 本地简单的推算：服务器给的进度 + (当前时间 -收到包的时间)
            return remotePosition.value + Math.max(0, performance.now() - progressAnchor) * (mediaMode.value === 'VIDEO' ? playbackRate.value : 1);
        }
    };

    const requireAuth = () => {
        if (!synchronized.value || upgradeRequired.value) return false;
        if (userStore.isGuest) {
            userStore.showNameModal = true;
            return false;
        }
        return true;
    };

    const checkCooldown = () => {
        const now = Date.now();
        if (now - lastControlTime.value < LOCAL_COOLDOWN) {
            // 这里可以不再弹 Toast 报错，而是静默失败，避免刷屏
            return false;
        }
        lastControlTime.value = now;
        return true;
    };

    // === 3. Actions ===

    const syncState = (state, generation = clientGeneration.value) => {
        const accepted = barrier.state(state, generation);
        if (!accepted) return false;
        if (accepted.changed) {
            pendingSeek = 0; localProgress.value = 0; isBuffering.value = false; isErrorState.value = false;
        }
        mediaMode.value = state.mediaMode;
        playbackEpoch.value = state.playbackEpoch; modeEpoch.value = state.modeEpoch;
        stateRevision.value = accepted.stateRevision; queueRevision.value = accepted.queueRevision;
        synchronized.value = true;
        sourceStatus.value = state.sourceStatus || 'EMPTY';
        sourceRetryAt.value = Number.isFinite(state.sourceRetryAt) ? Date.now() + Math.max(0, state.sourceRetryAt - state.serverTime) : null;
        const changed = state.nowPlaying?.playbackId !== nowPlaying.value?.playbackId;
        const jumped = changed || state.nowPlaying?.seekAvailableAt !== nowPlaying.value?.seekAvailableAt;
        if (changed || accepted.changed) { pendingSeek = 0; interaction.value = null; pendingInteraction = null; }
        videoFeatures.value = state.videoFeatures || {}; sourceErrorCode.value = state.sourceErrorCode || null;
        policyRecoveryRequired.value = state.policyRecoveryRequired === true; videoPending.value = state.videoPending || null;
        videoHistoryRevision.value = state.videoHistoryRevision || 0;
        if (state.nowPlaying) seekDeadline.value = Date.now() + Math.max(0, state.nowPlaying.seekAvailableAt - state.nowPlaying.serverTime);
        else seekDeadline.value = 0;
        nowPlaying.value = state.nowPlaying;
        if (!accepted.keepQueue) queue.value = Array.isArray(state.queue) ? state.queue : [];
        isPaused.value = state.isPaused;
        playIntent.value = state.playIntent === 'PLAYING' ? 'PLAYING' : state.playIntent === 'PAUSED' ? 'PAUSED' : state.isPaused ? 'PAUSED' : 'PLAYING';
        playbackRate.value = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].includes(state.playbackRate) ? state.playbackRate : 1;
        videoWait.value = state.mediaMode === 'VIDEO' ? state.videoWait || null : null;
        playMode.value = state.playMode || (state.isShuffle ? 'SHUFFLE' : 'SEQUENTIAL');
        isFairShuffle.value = state.isFairShuffle !== undefined ? state.isFairShuffle : true;
        allowOfflineShuffle.value = state.allowOfflineShuffle || false;
        isPauseLocked.value = state.isPauseLocked || false;
        isSkipLocked.value = state.isSkipLocked || false;
        isPlayModeLocked.value = state.isPlayModeLocked || false;
        isLoading.value = state.isLoading || false;
        streamListenerCount.value = state.streamListenerCount || 0;
        streamActive.value = state.isStreamEnabled || false;

        // 投票状态同步
        isVoteSkipEnabled.value = state.isVoteSkipEnabled || false;
        voteSkipThreshold.value = state.voteSkipThreshold || 0.5;
        voteSkipWaitTime.value = state.voteSkipWaitTime ?? 15;
        currentVotes.value = state.currentVotes || 0;
        eligibleUsers.value = state.eligibleUsers || 0;

        // 记录服务器发来的进度和收到包的时间
        if (state.nowPlaying) {
            const monotonicNow = performance.now(), estimated = serverClock.now(monotonicNow);
            const snapshotTime = state.serverTime ?? state.nowPlaying.serverTime;
            if (estimated !== null && Number.isFinite(snapshotTime) && Math.abs(estimated - snapshotTime) > 15000) sampleClock();
            remotePosition.value = state.nowPlaying.currentPosition + (!state.isPaused && estimated !== null
                && Number.isFinite(snapshotTime) && Math.abs(estimated - snapshotTime) <= 15000 ? Math.max(0, estimated - snapshotTime) * (state.mediaMode === 'VIDEO' ? playbackRate.value : 1) : 0);
            progressAnchor = monotonicNow;
            lastSyncTime.value = Date.now();
        } else {
            remotePosition.value = 0;
        }

        if (state.onlineUsers) {
            userStore.setOnlineUsers(state.onlineUsers);
        }

        if (state.config) {
            config.value = { ...state.config };
        }
        if (jumped) seekRevision.value++;
        if (pendingInteraction) receiveInteraction(pendingInteraction);
        if ((changed || accepted.changed || !interaction.value) && videoFeatures.value.interactionState) requestInteraction();
        return true;
    };

    const syncQueue = (message, generation = clientGeneration.value) => {
        if (barrier.queue(message, generation)) { queue.value = message.queue; queueRevision.value = message.queueRevision; return true; }
        if (synchronized.value && message?.playbackEpoch === playbackEpoch.value && message.modeEpoch > modeEpoch.value) resync();
        return false;
    };
    const acceptsEvent = event => !event?.playbackEpoch || (synchronized.value && barrier.matches(event));
    const receiveInteraction = message => {
        if (!synchronized.value || message?.protocolVersion !== 3 || message.roomId !== roomSession.roomId || message.playbackEpoch !== playbackEpoch.value || message.mediaMode !== mediaMode.value || message.modeEpoch !== modeEpoch.value || (message.playbackId ?? null) !== (nowPlaying.value?.playbackId ?? null)) return false;
        if (message.stateRevision > stateRevision.value) { if (!pendingInteraction || message.interactionRevision > pendingInteraction.interactionRevision) pendingInteraction = message; resync(); return false; }
        if (interaction.value && message.interactionRevision <= interaction.value.interactionRevision) return false;
        pendingInteraction = null; interaction.value = { ...message }; return true;
    };
    const requestInteraction = () => mediaMode.value === 'VIDEO' && videoFeatures.value.interactionState && sendMedia(WS_DEST.VIDEO_INTERACTION_RESYNC);
    const receiveVideoImport = message => {
        const result = message?.operation || message;
        if (!synchronized.value || result.protocolVersion !== 3 || result.roomId !== roomSession.roomId || result.playbackEpoch !== playbackEpoch.value) return false;
        const key = result.requestId || result.originalRequestId;
        if (!key) return false;
        const prior = videoImports.value[key];
        if (prior && (['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(prior.status) || message.kind !== 'IMPORT_ERROR') && (result.operationRevision ?? -1) <= (prior.operationRevision ?? -1)) return false;
        videoImports.value = { ...videoImports.value, [key]: { ...prior, ...result, kind: message.kind } }; return true;
    };
    const queryVideoImport = item => synchronized.value && !userStore.isGuest && socketService.send(WS_DEST.VIDEO_IMPORT_STATUS, { expectedPlaybackEpoch: playbackEpoch.value, originalRequestId: item.requestId, ...(item.operationId ? { operationId: item.operationId } : {}) });
    const recoverVideoSource = () => mediaMode.value === 'VIDEO' && policyRecoveryRequired.value && requireAuth() && sendMedia(WS_DEST.VIDEO_RECOVER, { playbackId: nowPlaying.value?.playbackId ?? null, sourceRevision: nowPlaying.value?.sourceRevision ?? 0, queueId: videoPending.value?.queueId ?? null });
    const operationId = createPlaybackRequestId;
    const playbackContext = () => synchronized.value ? { requestId: operationId(), clientGeneration: clientGeneration.value, expectedPlaybackEpoch: playbackEpoch.value,
        expectedMode: mediaMode.value, expectedModeEpoch: modeEpoch.value } : null;
    const isContextCurrent = context => synchronized.value && context?.clientGeneration === clientGeneration.value && context?.expectedPlaybackEpoch === playbackEpoch.value
        && context.expectedMode === mediaMode.value && context.expectedModeEpoch === modeEpoch.value;
    const sendMedia = (destination, payload = {}, context = playbackContext()) => {
        if (!isContextCurrent(context)) return false;
        const { clientGeneration: localGeneration, ...serverContext } = context;
        return socketService.send(destination, { ...payload, ...serverContext });
    };
    const resync = () => socketService.send(WS_DEST.RESYNC);
    const receiveClock = response => {
        const request = clockRequests.get(response?.requestId);
        if (!request || request.generation !== clientGeneration.value) return;
        clockRequests.delete(response.requestId);
        serverClock.sample({ ...request, ...response, clientReceivedAt: Date.now(), performanceReceivedAt: performance.now() });
        clockReady.value = serverClock.ready;
    };
    const sampleClock = () => {
        clockTimers.forEach(clearTimeout); clockTimers = []; clockRequests.clear(); serverClock.reset(); clockReady.value = false;
        const generation = clientGeneration.value;
        for (let i = 0; i < 5; i++) clockTimers.push(setTimeout(() => {
            if (!connected.value || generation !== clientGeneration.value) return;
            const requestId = operationId();
            clockRequests.set(requestId, { generation, clientSentAt: Date.now(), performanceSentAt: performance.now() });
            socketService.send(WS_DEST.CLOCK, { requestId });
        }, i * 100));
    };
    const beginSynchronization = generation => {
        clientGeneration.value = generation; barrier.reset(generation); synchronized.value = false;
        playbackEpoch.value = null; stateRevision.value = -1; queueRevision.value = -1;
        nowPlaying.value = null; queue.value = []; isPaused.value = true; isLoading.value = false;
        localProgress.value = 0; isBuffering.value = false; isErrorState.value = false; pendingSeek = 0;
        sourceStatus.value = 'EMPTY'; sourceRetryAt.value = null; interaction.value = null; pendingInteraction = null; videoFeatures.value = {}; sourceErrorCode.value = null; policyRecoveryRequired.value = false; videoPending.value = null;
        playIntent.value = 'PAUSED'; playbackRate.value = 1; videoWait.value = null;
        videoQualityOptions.value = []; actualVideoQuality.value = ''; videoDelivery.value = '';
        sampleClock();
    };
    const resetPlayback = () => {
        connected.value = false; beginSynchronization(clientGeneration.value + 1);
        clockTimers.forEach(clearTimeout); clockTimers = []; clockRequests.clear(); mediaMode.value = 'MUSIC';
    };

    const connect = () => {
        const authHeaders = {
            'user-name': userStore.currentUser.name || '游客',
            'user-token': userStore.userToken,
            'room-id': roomSession.roomId,
            'room-token': roomSession.roomToken,
            'management-token': roomSession.ownerAccess ? roomSession.managerToken : '',
            'playback-protocol': '3'
        };

        // 使用抽离出的订阅配置
        const subscriptions = createSocketSubscriptions();

        // 补充 UserMe 的特殊处理 (因为它需要用到 renameUser，如果放在 socketHandler 会导致循环依赖)
        subscriptions[WS_DEST.USER_ME] = (me) => {
            // me: { token, sessionId, name, isGuest }
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        subscriptions[WS_DEST.USER_ME_UPDATE] = (me) => {
            userStore.initUser(me.sessionId, me.name, me.isGuest);
        };

        const callbacks = createSocketCallbacks();

        socketService.connect(authHeaders, callbacks, subscriptions);
    };

    const tryReconnect = () => {
        if (!connected.value && !upgradeRequired.value) {
            socketService.forceReconnect();
        }
    };

    // --- 指令发送 ---
    const playNext = () => requireAuth() && checkCooldown() && sendMedia(WS_DEST.PLAYER_NEXT, { playbackId: nowPlaying.value?.playbackId ?? null });
    const togglePause = () => requireAuth() && checkCooldown() && sendMedia(WS_DEST.PLAYER_PAUSE, { playbackId: nowPlaying.value?.playbackId ?? null });
    const cyclePlayMode = () => requireAuth() && checkCooldown() && sendMedia(WS_DEST.PLAYER_SHUFFLE);
    const switchMediaMode = (mode, context) => roomSession.ownerAccess && requireAuth() && sendMedia(WS_DEST.MEDIA_MODE, { mode }, context);
    const refreshVideoSource = (reason = 'USER_REQUEST') => {
        if (mediaMode.value !== 'VIDEO' || !nowPlaying.value?.playbackId) return false;
        if (userStore.isGuest && reason !== 'USER_REQUEST') return false;
        return requireAuth() && sendMedia(WS_DEST.VIDEO_REFRESH, {
            playbackId: nowPlaying.value.playbackId, sourceRevision: nowPlaying.value.sourceRevision || 0, reason
        });
    };
    const reportVideoWatch = payload => mediaMode.value === 'VIDEO' && nowPlaying.value?.playbackId && sendMedia(WS_DEST.VIDEO_WATCH_STATUS, {
        playbackId: nowPlaying.value.playbackId, sourceRevision: nowPlaying.value.sourceRevision || 0, ...payload
    });
    const setVideoRate = rate => roomSession.ownerAccess && mediaMode.value === 'VIDEO' && requireAuth()
        && [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].includes(rate) && sendMedia(WS_DEST.VIDEO_RATE, { playbackId: nowPlaying.value?.playbackId ?? null, playbackRate: rate });
    const switchVideoPage = page => roomSession.ownerAccess && mediaMode.value === 'VIDEO' && !isSkipLocked.value && requireAuth()
        && Number.isSafeInteger(page) && page > 0 && nowPlaying.value?.playbackId
        && sendMedia(WS_DEST.VIDEO_PAGE, { playbackId: nowPlaying.value.playbackId, page });
    const seek = position => {
        if (!canSeek.value || Date.now() < Math.max(seekDeadline.value, pendingSeek) || !Number.isFinite(position)) return false;
        const duration = nowPlaying.value.music.duration;
        const target = Math.max(0, Math.min(duration - 1, Math.round(position)));
        if (!sendMedia(WS_DEST.PLAYER_SEEK, { playbackId: nowPlaying.value.playbackId, position: target })) return false;
        pendingSeek = Date.now() + 3000;
        seekDeadline.value = pendingSeek;
        return true;
    };

    const enqueue = (platform, musicId, context) => requireAuth() && sendMedia(WS_DEST.ENQUEUE, { platform, musicId }, context);
    const enqueuePlaylist = (platform, playlistId, context) => requireAuth() && sendMedia(WS_DEST.ENQUEUE_PLAYLIST, { platform, playlistId }, context);
    const topSong = (queueId) => requireAuth() && sendMedia(WS_DEST.QUEUE_TOP, { queueId });
    const removeSong = (queueId) => requireAuth() && sendMedia(WS_DEST.QUEUE_REMOVE, { queueId });

    const bindAccount = (platform, accountId, displayName = '', avatarUrl = '') => {
        socketService.send(WS_DEST.USER_BIND, { platform, accountId });
        userStore.updateBinding(platform, accountId, displayName, avatarUrl);
    };

    const renameUser = (newName) => {
        socketService.send(WS_DEST.USER_RENAME, { newName });
        // userStore.saveName(newName) removed; relying on backend sync
    };

    const sendLike = () => {
        // 上报当前本地播放位置（音频实际听到的位置），服务器用它作为打点，
        // 避免服务器时钟从歌曲开始计时、音频需缓冲后才起播导致打点超前于听感位置
        if (mediaMode.value === 'MUSIC' && requireAuth()) sendMedia(WS_DEST.PLAYER_LIKE, { position: Math.round(localProgress.value) });
    };

    const sendChatMessage = (content) => {
        if (userStore.isGuest) { userStore.showNameModal = true; return; }
        const { clientGeneration: localGeneration, ...context } = playbackContext() || {};
        socketService.send(WS_DEST.CHAT_SEND, { content, ...context });
    };

    // 歌词监听
    let metadataRequest = 0;
    watch([mediaMode, () => nowPlaying.value?.music?.platform, () => nowPlaying.value?.music?.id], async ([mode, platform, newId]) => {
        const request = ++metadataRequest;
        lyricText.value = '';
        chorusMarkers.value = [];
        if (!newId || mode === 'VIDEO') return;
        await Promise.allSettled([
            musicApi.getLyric(platform, newId).then(data => { if (request === metadataRequest) lyricText.value = data || ''; }),
            platform === 'netease' && /^\d+$/.test(newId) ? musicApi.getChorus(platform, newId).then(data => {
                if (request === metadataRequest) chorusMarkers.value = [...new Set((Array.isArray(data) ? data : []).filter(t => Number.isFinite(t) && t >= 0 && t < nowPlaying.value.music.duration))].sort((a,b) => a-b);
            }) : Promise.resolve()
        ]);
    });

    return {
        mediaMode, playbackEpoch, modeEpoch, stateRevision, queueRevision, clientGeneration, synchronized, upgradeRequired,
        videoFeatures, sourceErrorCode, policyRecoveryRequired, videoPending, videoHistoryRevision, interaction, videoImports, receiveInteraction, requestInteraction, receiveVideoImport, queryVideoImport, recoverVideoSource, sourceStatus, sourceRetryAt, clockReady, playbackContext, isContextCurrent, sendMedia, beginSynchronization, resetPlayback,
        syncQueue, acceptsEvent, receiveClock, sampleClock, resync, switchMediaMode, refreshVideoSource,
        nowPlaying, queue, isPaused, playIntent, intentPaused, playbackRate, videoWait, reportVideoWatch, setVideoRate, switchVideoPage,
        videoQualityOptions, actualVideoQuality, videoDelivery, playMode, isShuffle, isRepeatOne, isFairShuffle, allowOfflineShuffle, config,
        isPauseLocked, isSkipLocked, isPlayModeLocked, connected, isLoading, lyricText,
        localProgress, isBuffering, isErrorState, streamListenerCount, streamActive, chorusMarkers, canSeek, seekDeadline, seekRevision,
        isVoteSkipEnabled, voteSkipThreshold, voteSkipWaitTime, currentVotes, eligibleUsers,
        connect, tryReconnect, getCurrentProgress, syncState, // 导出 syncState
        playNext, togglePause, cyclePlayMode, seek,
        enqueue, enqueuePlaylist, topSong, removeSong,
        bindAccount, renameUser, sendChatMessage, sendLike
    };
});
