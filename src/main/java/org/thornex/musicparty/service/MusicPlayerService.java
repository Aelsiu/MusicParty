package org.thornex.musicparty.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.dto.PrivateDjSegment;
import org.thornex.musicparty.enums.CacheStatus;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.playback.PlaybackMailbox;
import org.thornex.musicparty.playback.PlaybackEventSink;
import org.thornex.musicparty.playback.PlaybackControlLimiter;
import reactor.core.Disposable;
import org.thornex.musicparty.event.*;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@org.thornex.musicparty.room.RoomScoped
@Slf4j
public class MusicPlayerService {

    private final Map<String, IMusicApiService> apiServiceMap;
    private final UserService userService;
    private final LocalCacheService localCacheService;
    // ChatService dependency removed to break circular reference
    private final LiveStreamService liveStreamService;

    // --- Refactored Dependencies ---
    private final MusicQueueManager queueManager;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties appProperties;
    private final NeteaseMusicApiService neteaseMusicApiService;
    private final PrivateDjService privateDjService;
    private PlaybackMailbox mailbox;
    private PlaybackEventSink eventSink;
    private PlaybackControlLimiter controlLimiter;
    private boolean active = true;
    private boolean closed;
    private long frozenSeekRemaining;
    private PendingMusic pending;
    private Disposable playbackRequest;
    private PlaybackMailbox.CompletionTicket playbackTicket;
    private final List<EnqueueOperation> enqueueOperations = new ArrayList<>();

    private enum PendingOrigin { QUEUE, HISTORY, FM, DJ }
    private static final class PendingMusic {
        final PendingOrigin origin;
        final MusicQueueItem item;
        final boolean forceFm;
        final String providerRequestId = UUID.randomUUID().toString();
        PrivateDjSegment segment;
        PendingMusic(PendingOrigin origin, MusicQueueItem item, boolean forceFm) { this.origin = origin; this.item = item; this.forceFm = forceFm; }
    }
    private static final class EnqueueOperation {
        final String token;
        final MusicQueueManager.QueueReservation quota;
        final PlaybackMailbox.CompletionTicket ticket;
        Disposable request;
        EnqueueOperation(String token, MusicQueueManager.QueueReservation quota, PlaybackMailbox.CompletionTicket ticket) {
            this.token = token; this.quota = quota; this.ticket = ticket;
        }
        void cancel() { quota.close(); if (ticket != null) ticket.cancel(); if (request != null) request.dispose(); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configurePlayback(PlaybackMailbox mailbox, PlaybackEventSink eventSink, PlaybackControlLimiter limiter) {
        this.mailbox = mailbox; this.eventSink = eventSink; this.controlLimiter = limiter;
    }

    // --- Player State ---
    private final AtomicReference<PlayableMusic> currentMusic = new AtomicReference<>(null);
    private final AtomicReference<String> currentEnqueuerId = new AtomicReference<>(null);
    private final AtomicReference<String> currentEnqueuerName = new AtomicReference<>(null);

    // 核心计时逻辑
    private final AtomicLong positionAnchor = new AtomicLong(0); // 上一次更新状态时的进度(ms)
    private final AtomicLong timestampAnchor = new AtomicLong(0); // 上一次更新状态时的系统时间(ms)
    private String playbackId;
    private long seekAvailableAt;
    private static final long SEEK_COOLDOWN_MS = 3000;


    private final AtomicBoolean isShuffle = new AtomicBoolean(false);
    private final AtomicReference<PlayMode> playMode = new AtomicReference<>(PlayMode.SEQUENTIAL);
    private final AtomicBoolean isFairShuffle;
    private final AtomicBoolean allowOfflineShuffle;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicBoolean isPauseLocked = new AtomicBoolean(false);
    private final AtomicBoolean isSkipLocked = new AtomicBoolean(false);
    private final AtomicBoolean isPlayModeLocked = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isStreamActive = new AtomicBoolean(false);

    // 投票切歌状态
    private final AtomicBoolean isVoteSkipEnabled = new AtomicBoolean(false);
    private final AtomicReference<Double> voteSkipThreshold = new AtomicReference<>(0.5);
    private final AtomicReference<Integer> voteSkipWaitTime = new AtomicReference<>(15);
    private final Set<String> skipVotes = ConcurrentHashMap.newKeySet();

    private final Map<String, Object> likeLock = new HashMap<>();
    private Set<String> currentLikedUserIds;
    private List<Long> currentLikeMarkers;

    private final AtomicLong lastControlTimestamp = new AtomicLong(0);
    private static final long GLOBAL_COOLDOWN_MS = 1000;
    private static final long IDLE_RESET_TIMEOUT_MS = Duration.ofMinutes(20).toMillis();

    private final AtomicLong playHeadVersion = new AtomicLong(0);

    // 私人FM/DJ 播放失败指数退避（5s→10s→20s→40s→80s，封顶 80s）
    private final AtomicLong fmDjRetryAt = new AtomicLong(0);
    private final AtomicInteger fmDjFailCount = new AtomicInteger(0);
    private static final long FM_DJ_MAX_BACKOFF_MS = 80_000L;

    // 当前播放段是否为 DJ 语音（语音段播完不进历史记录）
    private final AtomicBoolean currentIsVoice = new AtomicBoolean(false);

    public MusicPlayerService(List<IMusicApiService> apiServices, UserService userService,
                              LocalCacheService localCacheService,
                              LiveStreamService liveStreamService,
                              MusicQueueManager queueManager,
                              ApplicationEventPublisher eventPublisher,
                              AppProperties appProperties,
                              NeteaseMusicApiService neteaseMusicApiService,
                              PrivateDjService privateDjService) {
        this.apiServiceMap = apiServices.stream()
                .collect(Collectors.toMap(IMusicApiService::getPlatformName, Function.identity()));
        this.userService = userService;
        this.localCacheService = localCacheService;
        this.liveStreamService = liveStreamService;
        this.queueManager = queueManager;
        this.eventPublisher = eventPublisher;
        this.appProperties = appProperties;
        this.neteaseMusicApiService = neteaseMusicApiService;
        this.privateDjService = privateDjService;
        this.isFairShuffle = new AtomicBoolean(true);
        this.allowOfflineShuffle = new AtomicBoolean(false);
        this.currentLikedUserIds = ConcurrentHashMap.newKeySet();
        this.currentLikeMarkers = new CopyOnWriteArrayList<>();
    }

    @PostConstruct
    public void init() {
        log.info("MusicPlayerService initialized with {} API services: {}", apiServiceMap.size(), apiServiceMap.keySet());
        
        // 从配置初始化投票参数
        AppProperties.PlayerConfig playerConfig = appProperties.getPlayer();
        isVoteSkipEnabled.set(playerConfig.isVoteSkipEnabled());
        voteSkipThreshold.set(playerConfig.getVoteSkipThreshold());
        voteSkipWaitTime.set(playerConfig.getVoteSkipWaitTime());
        if (org.thornex.musicparty.room.RoomContext.current() != null) isPaused.set(true);
    }

    public synchronized void playerLoop() {
        if (!active || closed) return;
        if (isPaused.get()) {
            return;
        }

        PlayableMusic music = currentMusic.get();

        if (music != null) {
            long currentPos = calculateCurrentPosition();

            // 检查是否播放结束
            if (currentPos >= music.duration() && music.duration() > 0) {
                log.info("Song finished: {}", music.name());

                if (playMode.get() == PlayMode.REPEAT_ONE) {
                    // 单曲循环模式：重置进度，重新播放当前歌曲
                    // 前端 AudioEngine 通过 @seeked 事件检测进度跳变并自动重启播放
                    positionAnchor.set(0);
                    timestampAnchor.set(System.currentTimeMillis());
                    resetSeekState();
                    log.info("Repeat-one mode: restarting {}", music.name());
                    broadcastFullPlayerState();
                    return;
                }

                // DJ 语音段不进历史记录（spec/plan 明确要求；否则空队时会从历史取到语音并被当作真实网易云歌曲导致 NPE）
                if (!currentIsVoice.get()) {
                    Music finishedMusic = new Music(
                            music.id(),
                            music.name(),
                            music.artists(),
                            music.duration(),
                            music.platform(),
                            music.coverUrl()
                    );
                    queueManager.addToHistory(finishedMusic);
                }

                // 清空当前，触发下一首
                currentMusic.set(null);
                playNextInQueue();
            }
        } else {
            if (userService.getOnlineUserSummaries().isEmpty() && !isStreamActive.get()) {
                return;
            }
            if (System.currentTimeMillis() >= fmDjRetryAt.get()
                    && (shouldPlayPrivateFmDj() || !queueManager.getQueueSnapshot().isEmpty())) {
                playNextInQueue();
            }
        }
    }

    private synchronized void playNextInQueue() {
        if (!active || closed || (org.thornex.musicparty.room.RoomContext.current() != null && isPaused.get())) return;
        if (currentMusic.get() != null || isLoading.get()) return;
        reconcilePending();
        if (pending != null) { retryPending(); return; }
        syncFmMarker();
        if (shouldPlayPrivateFmDj()) { playFmDjNext(); return; }
        Map<String, QueueItemStatus> statusMap = buildStatusMap();
        MusicQueueItem nextItem = queueManager.pollNext(playMode.get(), isFairShuffle.get(), allowOfflineShuffle.get(),
                statusMap, userService.getRecentlyActiveUserTokens());
        if (isFmMarker(nextItem)) { playFmMarkerNext(nextItem); return; }
        if (nextItem == null) { broadcastFullPlayerState(); return; }
        if ((nextItem.status() == QueueItemStatus.FAILED || statusMap.get(nextItem.music().id()) == QueueItemStatus.FAILED)
                && !"bilibili".equalsIgnoreCase(nextItem.music().platform())) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, "SYSTEM", nextItem.music().name()));
            playNextInQueue(); return;
        }
        boolean history = "SYSTEM".equals(nextItem.enqueuedBy().token());
        pending = new PendingMusic(history ? PendingOrigin.HISTORY : PendingOrigin.QUEUE, nextItem, false);
        if (!history) queueManager.retainPending(nextItem);
        retryPending();
    }

    private void reconcilePending() {
        if (pending != null && pending.origin == PendingOrigin.QUEUE &&
                queueManager.getItem(pending.item.queueId()).isEmpty()) {
            cancelPlaybackAttempt(); pending = null;
        }
    }

    private void retryPending() {
        if (pending == null || !active || closed) return;
        if (pending.origin == PendingOrigin.FM || pending.origin == PendingOrigin.DJ) {
            startProviderPending(pending); return;
        }
        PendingMusic selected = pending;
        long version = playHeadVersion.incrementAndGet();
        isLoading.set(true);
        broadcastFullPlayerState();
        log.info("Playing next: {}", selected.item.music().name());
        try {
            requestPlayback(getApiService(selected.item.music().platform()).getPlayableMusic(selected.item.music().id())
                    .timeout(Duration.ofSeconds(10)), music -> {
                if (!isCurrentAttempt(selected, version)) return;
                if (selected.origin == PendingOrigin.QUEUE && queueManager.getItem(selected.item.queueId()).isEmpty()) {
                    pending = null; isLoading.set(false); broadcastFullPlayerState(); return;
                }
                if (selected.origin == PendingOrigin.QUEUE) queueManager.completePending(selected.item.queueId());
                pending = null;
                applyNewSong(music, selected.item);
            }, error -> {
                if (!isCurrentAttempt(selected, version)) return;
                log.warn("Play failed for {}: {}", selected.item.music().name(), error.getMessage());
                emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.ERROR_LOAD, "SYSTEM", selected.item.music().name()));
                // Preserve MUSIC's existing failed-song skip behavior; cancellation keeps pending separately.
                if (selected.origin == PendingOrigin.QUEUE) queueManager.completePending(selected.item.queueId());
                pending = null;
                isLoading.set(false); broadcastFullPlayerState(); playNextInQueue();
            });
        } catch (RuntimeException error) {
            isLoading.set(false); isPaused.set(true); broadcastFullPlayerState();
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "播放准备失败: " + error.getMessage()));
        }
    }

    private boolean isCurrentAttempt(PendingMusic selected, long version) {
        return !closed && active && pending == selected && playHeadVersion.get() == version && !isPaused.get();
    }

    /** Callbacks only transport immutable results; state writes happen in a reserved mailbox commit. */
    private <T> void requestPlayback(Mono<T> request, java.util.function.Consumer<T> success,
                                     java.util.function.Consumer<Throwable> failure) {
        PlaybackMailbox.CompletionTicket ticket = mailbox == null ? null : mailbox.reserveCompletion();
        playbackTicket = ticket;
        Disposable subscription = request.switchIfEmpty(Mono.error(new IllegalStateException("No playable result")))
                .subscribe(value -> commit(ticket, () -> success.accept(value)), error -> commit(ticket, () -> failure.accept(error)));
        playbackRequest = subscription;
    }

    private void commit(PlaybackMailbox.CompletionTicket ticket, Runnable callback) {
        if (ticket == null) { synchronized (this) { callback.run(); } }
        else ticket.commit(callback).exceptionally(error -> {
            if (!(error.getCause() instanceof PlaybackMailbox.RoomClosedException)) log.warn("Music completion rejected: {}", error.getMessage());
            return null;
        });
    }

    private void cancelPlaybackAttempt() {
        playHeadVersion.incrementAndGet();
        if (playbackTicket != null) playbackTicket.cancel();
        if (playbackRequest != null) playbackRequest.dispose();
        playbackTicket = null; playbackRequest = null;
        isLoading.set(false);
    }

    /** 播放源决策：托管 > (队列有有效歌曲则队列) > 填充空白 */
    boolean shouldPlayPrivateFmDj() {
        if (!active || closed) return false;
        AppProperties.PrivateDjConfig c = appProperties.getPrivateDj();
        if ("OFF".equals(c.getMode())) return false;
        if (c.isCustodyEnabled()) return true;
        if (c.isFillBlankEnabled()) {
            return !queueManager.hasPlayableItems(buildStatusMap());
        }
        return false;
    }

    /** Provider pending distinguishes voice/segment state from user queue items. */
    private void playFmDjNext() {
        if (!active || closed) return;
        if (pending == null) pending = new PendingMusic("DJ".equals(appProperties.getPrivateDj().getMode()) ? PendingOrigin.DJ : PendingOrigin.FM, null, false);
        startProviderPending(pending);
    }

    private void playFmMarkerNext(MusicQueueItem marker) {
        syncFmMarker();
        if (!active || closed) return;
        pending = new PendingMusic(PendingOrigin.FM, null, true);
        startProviderPending(pending);
    }

    private void startProviderPending(PendingMusic selected) {
        long version = playHeadVersion.incrementAndGet();
        isLoading.set(true);
        broadcastFullPlayerState();
        if (selected.segment != null) { loadFmDjPlayable(selected.segment, version, selected.forceFm); return; }
        Mono<PrivateDjSegment> provider = selected.forceFm ? privateDjService.nextFmSegment() : privateDjService.nextSegment();
        try {
            requestPlayback(provider.timeout(Duration.ofSeconds(15)), segment -> {
                if (pending != selected || closed) return;
                // Retain a segment already taken from a provider batch even if freezing won the queue race.
                selected.segment = segment;
                if (isCurrentAttempt(selected, version)) loadFmDjPlayable(segment, version, selected.forceFm);
            }, error -> handleFmDjError(version, error));
        } catch (RuntimeException error) { handleFmDjError(version, error); }
    }

    private boolean isFmMarker(MusicQueueItem item) {
        return item != null && MusicQueueManager.FM_MARKER_ID.equals(item.music().platform());
    }

    /**
     * 加入队列功能：SHUFFLE + 模式非关闭(OFF) + 加入队列且非托管时确保 FM 标记在队；
     * 任一条件不满足时移除标记（对称清理，防止陈旧标记在 SEQUENTIAL 等路径被选中触发 FM 播放）。
     */
    void syncFmMarker() {
        AppProperties.PrivateDjConfig c = appProperties.getPrivateDj();
        boolean shouldPresent = !"OFF".equals(c.getMode()) && c.isJoinQueueEnabled()
                && !c.isCustodyEnabled() && playMode.get() == PlayMode.SHUFFLE;
        if (shouldPresent) {
            queueManager.ensureFmMarker();
        } else {
            queueManager.removeFmMarker();
        }
    }

    // ---- 测试辅助（仅测试使用）----
    void setPlayModeForTest(PlayMode mode) { playMode.set(mode); }
    void syncFmMarkerForTest() { syncFmMarker(); }
    void playFmMarkerNextForTest(MusicQueueItem marker) { playFmMarkerNext(marker); }
    void setPositionForTest(long positionMs) { positionAnchor.set(positionMs); }
    void applyFmDjSegmentForTest(PlayableMusic music, PrivateDjSegment segment) { applyFmDjSegment(music, segment, false); }
    void setPausedForTest(boolean paused) { isPaused.set(paused); }

    private void loadFmDjPlayable(PrivateDjSegment segment, long version, boolean forceFm) {
        Mono<PlayableMusic> playableMono;
        if (segment instanceof PrivateDjSegment.Song s) {
            // FM/DJ 推荐歌曲用 eapi /song/url 取直链（普通点播的 getPlayableMusic 用 /song/url/v1，
            // 对 fee=1/8 推荐歌曲会 404），并从段信息直接构造，省一次 /song/detail。
            playableMono = neteaseMusicApiService.getFmDjSongUrl(s.songId())
                    .map(songUrl -> new PlayableMusic(
                            s.songId(), s.name(), s.artists(), s.durationMs(),
                            "netease", songUrl.url(), s.coverUrl(), false, songUrl.actualQuality()));
        } else if (segment instanceof PrivateDjSegment.Voice v) {
            playableMono = Mono.just(new PlayableMusic(
                    v.voiceId(), "AI DJ", List.of("私人DJ"), v.durationMs(),
                    "netease", v.voiceUrl(), v.relatedCoverUrl(), false));
        } else {
            handleFmDjError(version, new IllegalStateException("Unknown segment"));
            return;
        }
        PendingMusic selected = pending;
        try {
            requestPlayback(playableMono.timeout(Duration.ofSeconds(10)), music -> {
                if (selected == null || !isCurrentAttempt(selected, version)) return;
                pending = null;
                applyFmDjSegment(music, segment, forceFm);
            }, error -> handleFmDjError(version, error));
        } catch (RuntimeException error) { handleFmDjError(version, error); }
    }

    private synchronized void applyFmDjSegment(PlayableMusic music, PrivateDjSegment segment, boolean forceFm) {
        currentLikedUserIds.clear();
        currentLikeMarkers.clear();
        skipVotes.clear();
        currentMusic.set(music);
        currentIsVoice.set(segment instanceof PrivateDjSegment.Voice);
        currentEnqueuerId.set(MusicQueueManager.FM_MARKER_USER_TOKEN);
        boolean djMode = "DJ".equals(appProperties.getPrivateDj().getMode());
        currentEnqueuerName.set(forceFm ? "私人FM" : (djMode ? "私人DJ" : "私人FM"));
        positionAnchor.set(0);
        timestampAnchor.set(System.currentTimeMillis());
        resetSeekState();
        isPaused.set(false);
        isLoading.set(false);
        fmDjFailCount.set(0);
        fmDjRetryAt.set(0);

        if (segment instanceof PrivateDjSegment.Song s) {
            queueManager.addToHistory(new Music(s.songId(), s.name(), s.artists(),
                    s.durationMs(), "netease", s.coverUrl()));
        }

        log.info("Now playing (private {}): {}", djMode ? "DJ" : "FM", music.name());
        broadcastFullPlayerState();
        broadcastQueueUpdate();
        emit(new SystemMessageEvent(this,
                SystemMessageEvent.Level.INFO, PlayerAction.PLAY_START, "__FM__", music.name()));
    }

    private void handleFmDjError(long version, Throwable error) {
        if (!active || closed || playHeadVersion.get() != version) return;
        log.error("Private FM/DJ segment failed: {}", error.getMessage());
        int fails = fmDjFailCount.incrementAndGet();
        long backoff = Math.min(5_000L << Math.min(fails - 1, 4), FM_DJ_MAX_BACKOFF_MS);
        fmDjRetryAt.set(System.currentTimeMillis() + backoff);
        isLoading.set(false);
        broadcastFullPlayerState();
        emit(new SystemMessageEvent(this,
                SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "私人FM/DJ 获取失败，稍后自动重试"));
    }

    private long calculateCurrentPosition() {
        if (currentMusic.get() == null) return 0;
        if (isPaused.get()) {
            return positionAnchor.get();
        } else {
            long now = System.currentTimeMillis();
            long elapsed = now - timestampAnchor.get();
            return positionAnchor.get() + elapsed;
        }
    }

    private synchronized void applyNewSong(PlayableMusic music, MusicQueueItem queueItem) {
        currentLikedUserIds.clear();
        currentLikeMarkers.clear();
        skipVotes.clear(); // 切歌时清空投票

        // 重置计时器
        currentMusic.set(music);
        currentIsVoice.set(false);
        currentEnqueuerId.set(queueItem.enqueuedBy().token());
        currentEnqueuerName.set(queueItem.enqueuedBy().name());

        positionAnchor.set(0);
        timestampAnchor.set(System.currentTimeMillis());
        resetSeekState();
        isPaused.set(false);

        log.info("Now playing: {}", music.name());
        isLoading.set(false);
        broadcastFullPlayerState();
        broadcastQueueUpdate();

        // 发布开始播放事件 (用于聊天栏展示)
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.PLAY_START, queueItem.enqueuedBy().token(), queueItem.enqueuedBy().name(), music.name()));
    }

    private void resetSeekState() {
        playbackId = UUID.randomUUID().toString();
        seekAvailableAt = 0;
    }

    public synchronized boolean seek(SeekRequest request, String sessionId, boolean manager) {
        if (!active || closed) return rejectSeek(sessionId, "当前音乐模式未激活");
        var user = userService.getUser(sessionId).orElse(null);
        if (user == null || user.isGuest()) return false;
        String policy = appProperties.getPlayer().getSeekPolicy();
        boolean allowed = "ALL".equals(policy) || ("OWNER_AND_ENQUEUER".equals(policy)
                && (manager || user.getToken().equals(currentEnqueuerId.get())));
        if (!allowed) return rejectSeek(sessionId, "本房间不允许你跳转播放进度");
        PlayableMusic music = currentMusic.get();
        if (request == null || music == null || isLoading.get() || music.duration() <= 0
                || playbackId == null || !playbackId.equals(request.playbackId()))
            return rejectSeek(sessionId, "歌曲已变化，请等待播放状态更新");
        if (request.position() == null || request.position() < 0 || request.position() >= music.duration())
            return rejectSeek(sessionId, "跳转位置超出歌曲时长");
        long now = System.currentTimeMillis();
        if (now < seekAvailableAt) return rejectSeek(sessionId, "房间跳转冷却中，请稍后再试");
        positionAnchor.set(request.position());
        timestampAnchor.set(now);
        seekAvailableAt = now + SEEK_COOLDOWN_MS;
        broadcastFullPlayerState();
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                PlayerAction.JUMP, user.getToken(), null));
        return true;
    }

    private boolean rejectSeek(String sessionId, String message) {
        emit(new SeekRejectedEvent(sessionId, getUserToken(sessionId), message));
        return false;
    }

    public synchronized PlayerState getCurrentPlayerState() {
        PlayableMusic music = currentMusic.get();
        NowPlayingInfo infoToSend = null;

        if (music != null) {
            infoToSend = new NowPlayingInfo(
                    music,
                    calculateCurrentPosition(), // 直接返回计算好的进度
                    currentEnqueuerId.get(),
                    currentEnqueuerName.get(),
                    Set.copyOf(currentLikedUserIds),
                    List.copyOf(currentLikeMarkers),
                    playbackId,
                    seekAvailableAt,
                    System.currentTimeMillis()
            );
        }

        Set<String> onlineTokens = userService.getRecentlyActiveUserTokens();
        int currentVoteCount = (int) skipVotes.stream().filter(onlineTokens::contains).count();
        int eligibleCount = calculateEligibleUsers(onlineTokens);

        PlayMode currentPlayMode = playMode.get();
        return new PlayerState(
                infoToSend,
                getQueueWithUpdatedStatus(),
                currentPlayMode.name(),
                currentPlayMode == PlayMode.SHUFFLE,
                isFairShuffle.get(),
                allowOfflineShuffle.get(),
                userService.getOnlineUserSummaries(),
                isPaused.get(),
                isPauseLocked.get(),
                isSkipLocked.get(),
                isPlayModeLocked.get(),
                isLoading.get(),
                liveStreamService.getStreamListenerCount(),
                liveStreamService.isEnabled(),
                isVoteSkipEnabled.get(),
                voteSkipThreshold.get(),
                voteSkipWaitTime.get(),
                currentVoteCount,
                eligibleCount,
                new PlayerState.AppConfigSummary(
                        appProperties.getQueue().getMaxSize(),
                        appProperties.getQueue().getHistorySize(),
                        appProperties.getQueue().getMaxUserSongs(),
                        appProperties.getPlayer().getMaxPlaylistImportSize(),
                        appProperties.getChat().getMaxHistorySize(),
                        appProperties.getChat().getMinIntervalMs(),
                        appProperties.getChat().getMaxMessageLength(),
                        appProperties.getNetease().getQuality(),
                        appProperties.getNetease().isEnabled(),
                        appProperties.getBilibili().isEnabled(),
                        appProperties.getBilibili().getMaxDurationMinutes(),
                        isVoteSkipEnabled.get(),
                        voteSkipThreshold.get(),
                        voteSkipWaitTime.get(),
                        neteaseMusicApiService.isCookieConfigured(),
                        new PlayerState.AppConfigSummary.PrivateDjConfigSummary(
                                appProperties.getPrivateDj().getMode(),
                                appProperties.getPrivateDj().isFillBlankEnabled(),
                                appProperties.getPrivateDj().isJoinQueueEnabled(),
                                appProperties.getPrivateDj().isCustodyEnabled()
                        ),
                        appProperties.getPlayer().getSeekPolicy(),
                        appProperties.getPlayer().getVideoMaxDurationMinutes(),
                        appProperties.getBilibili().getCookie() != null && !appProperties.getBilibili().getCookie().isBlank(),
                        appProperties.getPlayer().getVideoMaxResolution(), appProperties.getPlayer().getVideoPolicyRevision(),appProperties.isXifanEnabled()
                )
        );
    }

    private int calculateEligibleUsers(Set<String> onlineTokens) {
        String enqueuerToken = currentEnqueuerId.get();
        // 必须是在线、活跃、非游客、且不是点歌者
        return (int) userService.getOnlineUserSummaries().stream()
                .filter(u -> !u.isGuest())
                .filter(u -> onlineTokens.contains(u.token()))
                .filter(u -> !u.token().equals(enqueuerToken))
                .count();
    }

    public void toggleFairShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;
        boolean current;
        boolean newState;
        do {
            current = isFairShuffle.get();
            newState = !current;
        } while (!isFairShuffle.compareAndSet(current, newState));

        log.info("Fair shuffle mode set to {} by {}", newState, getUserName(sessionId));
        broadcastFullPlayerState();
        broadcastQueueUpdate(); // 可能会影响前端展示
    }

    public void toggleAllowOfflineShuffle(String sessionId) {
        if (isRateLimited(sessionId)) return;
        boolean current;
        boolean newState;
        do {
            current = allowOfflineShuffle.get();
            newState = !current;
        } while (!allowOfflineShuffle.compareAndSet(current, newState));

        log.info("Allow offline shuffle set to {} by {}", newState, getUserName(sessionId));
        broadcastFullPlayerState();
    }

    public int clearOfflineSongs() {
        Set<String> onlineTokens = userService.getRecentlyActiveUserTokens();
        List<MusicQueueItem> snapshot = queueManager.getQueueSnapshot();
        int removedCount = 0;
        for (MusicQueueItem item : snapshot) {
            if (!onlineTokens.contains(item.enqueuedBy().token())) {
                queueManager.remove(item.queueId());
                removedCount++;
            }
        }
        for (EnqueueOperation operation : new ArrayList<>(enqueueOperations)) {
            if (!onlineTokens.contains(operation.token)) cancelEnqueues(operation.token);
        }
        reconcilePending();
        log.info("Cleared {} songs from offline users.", removedCount);
        broadcastQueueUpdate();
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "管理员已清理 " + removedCount + " 首离线成员的点播歌曲"));
        return removedCount;
    }

    public void setLock(String type, boolean locked) {
        AtomicBoolean targetLock;
        String desc;
        switch (type.toUpperCase()) {
            case "PAUSE" -> { targetLock = isPauseLocked; desc = "暂停"; }
            case "SKIP" -> { targetLock = isSkipLocked; desc = "切歌"; }
            case "SHUFFLE" -> { targetLock = isPlayModeLocked; desc = "播放模式"; }
            default -> throw new IllegalArgumentException("Unknown lock type");
        }

        boolean old = targetLock.getAndSet(locked);
        if (old != locked) {
            log.info("{} lock set to: {}", desc, locked);
            broadcastFullPlayerState();
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM",
                    locked ? "管理员锁定了" + desc : "管理员解锁了" + desc));
        }
    }

    public void setAllLocks(boolean locked) {
        isPauseLocked.set(locked);
        isSkipLocked.set(locked);
        isPlayModeLocked.set(locked);
        broadcastFullPlayerState();
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM",
                locked ? "管理员锁定了所有控制" : "管理员解锁了所有控制"));
    }

    /** 捕获当前播放相关运行时设置（持久化用）。只读 Atomic 字段——它们是 updateConfig 的运行时真相。 */
    public SettingsSnapshot.PlayerSettings getPlayerSettings() {
        return new SettingsSnapshot.PlayerSettings(
                playMode.get().name(),
                isFairShuffle.get(),
                allowOfflineShuffle.get(),
                isVoteSkipEnabled.get(),
                voteSkipThreshold.get(),
                voteSkipWaitTime.get(),
                isPauseLocked.get(),
                isSkipLocked.get(),
                isPlayModeLocked.get());
    }

    /** 回填持久化的播放设置。逐字段判空；playMode 恢复时同步 isShuffle 保持派生一致。 */
    public void applyPlayerSettings(SettingsSnapshot.PlayerSettings s) {
        if (s == null) return;
        if (s.playMode() != null) {
            try {
                PlayMode mode = PlayMode.valueOf(s.playMode());
                playMode.set(mode);
                isShuffle.set(mode == PlayMode.SHUFFLE);
            } catch (IllegalArgumentException e) {
                log.warn("Ignored invalid persisted playMode: {}", s.playMode());
            }
        }
        if (s.fairShuffle() != null) isFairShuffle.set(s.fairShuffle());
        if (s.allowOfflineShuffle() != null) allowOfflineShuffle.set(s.allowOfflineShuffle());
        if (s.voteSkipEnabled() != null) { isVoteSkipEnabled.set(s.voteSkipEnabled()); appProperties.getPlayer().setVoteSkipEnabled(s.voteSkipEnabled()); }
        if (s.voteSkipThreshold() != null) { voteSkipThreshold.set(s.voteSkipThreshold()); appProperties.getPlayer().setVoteSkipThreshold(s.voteSkipThreshold()); }
        if (s.voteSkipWaitTime() != null) { voteSkipWaitTime.set(s.voteSkipWaitTime()); appProperties.getPlayer().setVoteSkipWaitTime(s.voteSkipWaitTime()); }
        if (s.pauseLocked() != null) isPauseLocked.set(s.pauseLocked());
        if (s.skipLocked() != null) isSkipLocked.set(s.skipLocked());
        if (s.playModeLocked() != null) isPlayModeLocked.set(s.playModeLocked());
    }

    /** Shared room configuration is authoritative for both playback engines. */
    public void refreshSharedPlayerConfig() {
        AppProperties.PlayerConfig config = appProperties.getPlayer();
        isVoteSkipEnabled.set(config.isVoteSkipEnabled());
        voteSkipThreshold.set(config.getVoteSkipThreshold());
        voteSkipWaitTime.set(config.getVoteSkipWaitTime());
    }

    public void enqueue(EnqueueRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();

        // Check platform enabled
        if ("netease".equalsIgnoreCase(request.platform()) && !appProperties.getNetease().isEnabled()) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "添加失败: 网易云音乐源已被禁用"));
            return;
        }
        if ("bilibili".equalsIgnoreCase(request.platform()) && !appProperties.getBilibili().isEnabled()) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "添加失败: Bilibili 源已被禁用"));
            return;
        }

        startEnqueue(enqueuer, 1, count -> getApiService(request.platform()).getPlayableMusic(request.musicId())
                .map(playable -> List.of(new Music(playable.id(), playable.name(), playable.artists(), playable.duration(), playable.platform(), playable.coverUrl()))),
                request.platform(), PlayerAction.ADD);
    }

    // 点赞逻辑
    public void likeSong(String sessionId, Long clientPositionMs) {
        PlayableMusic music = currentMusic.get();
        if (music == null) return;

        String token = getUserToken(sessionId);

        // 1. 检查去重 (单人单曲一次)
        if (currentLikedUserIds.contains(token)) return;

        // 2. 更新数据
        currentLikedUserIds.add(token);

        // 3. 打点位置：优先用客户端上报的音频位置（用户实际听到的时刻），
        //    否则回退到服务器推算进度。服务器时钟在 applyNewSong 时就开始走、
        //    音频要等缓冲/转码后才起播，直接用服务器进度会让打点超前于听感位置。
        long progress;
        if (clientPositionMs != null) {
            long duration = music.duration();
            progress = duration > 0
                    ? Math.max(0, Math.min(duration, clientPositionMs))
                    : Math.max(0, clientPositionMs);
        } else {
            progress = calculateCurrentPosition();
        }
        currentLikeMarkers.add(progress);

        log.info("Like received from {}", getUserName(sessionId));

        // 3. 广播
        // 广播事件用于触发特效
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, PlayerAction.LIKE, token, music.name()));
        // 广播状态更新进度条打点和用户列表
        broadcastFullPlayerState();
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String sessionId) {
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty()) return;
        User enqueuer = userOpt.get();

        // Check platform enabled
        if ("netease".equalsIgnoreCase(request.platform()) && !appProperties.getNetease().isEnabled()) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: 网易云音乐源已被禁用"));
            return;
        }
        if ("bilibili".equalsIgnoreCase(request.platform()) && !appProperties.getBilibili().isEnabled()) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, enqueuer.getToken(), "导入失败: Bilibili 源已被禁用"));
            return;
        }

        startEnqueue(enqueuer, appProperties.getPlayer().getMaxPlaylistImportSize(),
                count -> getApiService(request.platform()).getPlaylistMusics(request.playlistId(), 0, count),
                request.platform(), PlayerAction.IMPORT_PLAYLIST);
    }

    private void startEnqueue(User enqueuer, int requested,
                              java.util.function.Function<Integer, Mono<List<Music>>> lookup,
                              String platform, PlayerAction action) {
        if (closed) return;
        MusicQueueManager.QueueReservation quota = queueManager.reserve(enqueuer.getToken(), requested);
        if (quota.count() == 0) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE,
                    enqueuer.getToken(), "添加失败: 房间队列或您的点播数量已达上限"));
            return;
        }
        PlaybackMailbox.CompletionTicket ticket;
        try { ticket = mailbox == null ? null : mailbox.reserveCompletion(); }
        catch (RuntimeException error) { quota.close(); throw error; }
        EnqueueOperation operation = new EnqueueOperation(enqueuer.getToken(), quota, ticket);
        UserSummary requester = new UserSummary(enqueuer.getToken(), enqueuer.getSessionId(), enqueuer.getName(), enqueuer.isGuest());
        enqueueOperations.add(operation);
        try {
            operation.request = lookup.apply(quota.count()).timeout(Duration.ofSeconds(30)).defaultIfEmpty(List.of())
                    .subscribe(musics -> commit(ticket, () -> {
                        enqueueOperations.remove(operation);
                        if (!quota.release() || closed) return;
                        int count = 0;
                        String singleName = null;
                        QueueItemStatus status = "bilibili".equals(platform) ? QueueItemStatus.PENDING : QueueItemStatus.READY;
                        for (Music music : musics.stream().limit(quota.count()).toList()) {
                            MusicQueueItem item = queueManager.add(music, requester, status);
                            if (item != null) {
                                count++; singleName = music.name();
                                if ("bilibili".equals(platform)) getApiService(platform).prefetchMusic(music.id());
                            }
                        }
                        broadcastQueueUpdate();
                        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.SUCCESS, action,
                                requester.token(), requester.name(), action == PlayerAction.ADD ? (singleName == null ? "已在队列中" : singleName) : String.valueOf(count)));
                    }), error -> commit(ticket, () -> {
                        enqueueOperations.remove(operation);
                        if (!quota.release() || closed) return;
                        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE,
                                requester.token(), "添加失败: " + error.getMessage()));
                    }));
        } catch (RuntimeException error) {
            operation.cancel(); enqueueOperations.remove(operation); throw error;
        }
    }

    public synchronized void topSong(String queueId, String sessionId) {
        if (Objects.equals(getQueuePendingId(), queueId) && queueId != null) throw new IllegalArgumentException("ITEM_PENDING");
        // 先调用 top 执行置顶操作
        TopResult result = queueManager.top(queueId, playMode.get());
        
        if (result != TopResult.NONE) {
            log.info("Song topped ({}) request by {}", result, getUserName(sessionId));
            broadcastQueueUpdate();

            // 只有全局置顶才发送系统消息广播
            if (result == TopResult.GLOBAL) {
                String songName = queueManager.getItem(queueId)
                        .map(item -> item.music().name())
                        .orElse("未知歌曲");
                emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.TOP, getUserToken(sessionId), songName));
            }
            
            if (currentMusic.get() == null) {
                playNextInQueue();
            }
        }
    }

    public void removeSongFromQueue(String queueId, String sessionId) {
        removeSongFromQueue(queueId, sessionId, false);
    }

    public String getQueuePendingId() {
        return pending != null && pending.origin == PendingOrigin.QUEUE && pending.item != null ? pending.item.queueId() : null;
    }

    public synchronized boolean cancelTop(String queueId) {
        if (queueId != null && Objects.equals(getQueuePendingId(), queueId)) throw new IllegalArgumentException("ITEM_PENDING");
        boolean changed = queueManager.cancelTop(queueId);
        if (changed) broadcastQueueUpdate();
        return changed;
    }

    public record MusicHistorySnapshot(long revision, List<Music> items) {}
    private MusicHistorySnapshot observedMusicHistory = new MusicHistorySnapshot(0, List.of());
    public synchronized MusicHistorySnapshot getMusicHistorySnapshot() {
        List<Music> items = queueManager.getHistorySnapshot().stream()
            .filter(item -> !MusicQueueManager.FM_MARKER_ID.equals(item.id()))
            .map(item -> new Music(item.id(), item.name(), item.artists() == null ? List.of() : List.copyOf(item.artists()),
                item.duration(), item.platform(), item.coverUrl())).toList();
        if (!items.equals(observedMusicHistory.items())) {
            observedMusicHistory = new MusicHistorySnapshot(observedMusicHistory.revision() + 1, items);
        }
        return observedMusicHistory;
    }

    public void removeSongFromQueue(String queueId, String sessionId, boolean manager) {
        var user = userService.getUser(sessionId).orElse(null);
        if (user == null || user.isGuest()) return;
        Optional<MusicQueueItem> removedItem = queueManager.removeAuthorized(queueId, user.getToken(), manager);
        if (removedItem.isPresent()) {
            reconcilePending();
            log.info("Removed song from queue by {}", getUserName(sessionId));
            broadcastQueueUpdate();
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.REMOVE, getUserToken(sessionId), removedItem.get().music().name()));
        }
    }

    public void skipToNext(String sessionId) {
        if (!active || closed) return;
        if (isRateLimited(sessionId)) return;
        
        // 管理员通过控制面板切歌 (SYSTEM) 或未开启投票模式，直接切歌
        if ("SYSTEM".equals(sessionId) || !isVoteSkipEnabled.get()) {
            executeSkip(sessionId);
            return;
        }

        // 投票模式逻辑
        handleVoteSkip(sessionId);
    }

    private void handleVoteSkip(String sessionId) {
        if (currentMusic.get() == null) return;
        
        Optional<User> userOpt = userService.getUser(sessionId);
        if (userOpt.isEmpty() || userOpt.get().isGuest()) return;
        
        String token = userOpt.get().getToken();
        String enqueuerToken = currentEnqueuerId.get();

        // 特殊逻辑：点歌者切自己的歌，视为强制切歌，不走投票逻辑，且不设 15 秒限制
        if (token.equals(enqueuerToken)) {
            log.info("Enqueuer {} skipped their own song.", userOpt.get().getName());
            executeSkip(sessionId);
            return;
        }

        // 1. 检查播放时长 (15秒限制，仅针对投票者)
        long currentPos = calculateCurrentPosition();
        if (currentPos < voteSkipWaitTime.get() * 1000L) {
            return;
        }

        // 2. 切换投票状态 (Toggle)
        if (skipVotes.contains(token)) {
            skipVotes.remove(token);
        } else {
            skipVotes.add(token);
        }

        // 3. 判定是否达成条件
        checkVoteSkipThreshold();
        
        // 4. 广播状态更新 (用于前端角标)
        broadcastFullPlayerState();
    }

    private void checkVoteSkipThreshold() {
        if (!active || closed) return;
        Set<String> onlineTokens = userService.getRecentlyActiveUserTokens();
        int currentVoteCount = (int) skipVotes.stream().filter(onlineTokens::contains).count();
        int eligibleCount = calculateEligibleUsers(onlineTokens);

        if (eligibleCount > 0 && (double) currentVoteCount / eligibleCount >= voteSkipThreshold.get()) {
            String msg = String.format("投票切歌通过！(%d/%d 票)，正在进入下一首", currentVoteCount, eligibleCount);
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", msg));
            executeSkip("SYSTEM");
        }
    }

    private synchronized void executeSkip(String sessionId) {
        if (isSkipLocked.get() && !"SYSTEM".equals(sessionId)) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, getUserToken(sessionId), "切歌功能已被锁定"));
            return;
        }

        // 切歌时版本号自增，废弃之前的任何 pending 请求
        cancelPlaybackAttempt();
        if (pending != null && pending.origin == PendingOrigin.QUEUE) queueManager.completePending(pending.item.queueId());
        pending = null;

        currentMusic.set(null);
        positionAnchor.set(0);

        if (!"SYSTEM".equals(sessionId)) {
            emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, PlayerAction.SKIP, getUserToken(sessionId), null));
        }
        playNextInQueue();
    }

    public synchronized void updateConfig(AdminConfigUpdateRequest request) {
        StringBuilder logMsg = new StringBuilder("System configuration updated: ");
        if (request.seekPolicy() != null) appProperties.getPlayer().setSeekPolicy(request.seekPolicy());
        
        if (request.maxSize() != null) {
            appProperties.getQueue().setMaxSize(request.maxSize());
            logMsg.append("MaxQueueSize=").append(request.maxSize()).append(" ");
        }
        if (request.historySize() != null) {
            appProperties.getQueue().setHistorySize(request.historySize());
            logMsg.append("HistorySize=").append(request.historySize()).append(" ");
        }
        if (request.maxUserSongs() != null) {
            appProperties.getQueue().setMaxUserSongs(request.maxUserSongs());
            logMsg.append("MaxUserSongs=").append(request.maxUserSongs()).append(" ");
        }
        if (request.maxPlaylistImportSize() != null) {
            appProperties.getPlayer().setMaxPlaylistImportSize(request.maxPlaylistImportSize());
            logMsg.append("MaxPlaylistImportSize=").append(request.maxPlaylistImportSize()).append(" ");
        }
        if (request.maxChatHistorySize() != null) {
            appProperties.getChat().setMaxHistorySize(request.maxChatHistorySize());
            logMsg.append("MaxChatHistorySize=").append(request.maxChatHistorySize()).append(" ");
        }
        if (request.minChatIntervalMs() != null) {
            appProperties.getChat().setMinIntervalMs(request.minChatIntervalMs());
            logMsg.append("MinChatInterval=").append(request.minChatIntervalMs()).append("ms ");
        }
        if (request.maxChatMessageLength() != null) {
            appProperties.getChat().setMaxMessageLength(request.maxChatMessageLength());
            logMsg.append("MaxChatMessageLength=").append(request.maxChatMessageLength()).append(" ");
        }
        if (request.neteaseQuality() != null) {
            appProperties.getNetease().setQuality(request.neteaseQuality());
            logMsg.append("NeteaseQuality=").append(request.neteaseQuality()).append(" ");
        }
        if (request.neteaseEnabled() != null) {
            appProperties.getNetease().setEnabled(request.neteaseEnabled());
            logMsg.append("NeteaseEnabled=").append(request.neteaseEnabled()).append(" ");
        }
        if(request.xifanEnabled()!=null) appProperties.setXifanEnabled(request.xifanEnabled());
        if (request.bilibiliEnabled() != null) {
            appProperties.getBilibili().setEnabled(request.bilibiliEnabled());
            logMsg.append("BilibiliEnabled=").append(request.bilibiliEnabled()).append(" ");
        }
        if (request.bilibiliMaxDurationMinutes() != null) {
            appProperties.getBilibili().setMaxDurationMinutes(request.bilibiliMaxDurationMinutes());
            logMsg.append("BilibiliMaxDurationMinutes=").append(request.bilibiliMaxDurationMinutes()).append(" ");
        }

        if (request.voteSkipEnabled() != null) {
            isVoteSkipEnabled.set(request.voteSkipEnabled());
            appProperties.getPlayer().setVoteSkipEnabled(request.voteSkipEnabled());
            logMsg.append("VoteSkipEnabled=").append(request.voteSkipEnabled()).append(" ");
        }
        if (request.voteSkipThreshold() != null) {
            voteSkipThreshold.set(request.voteSkipThreshold());
            appProperties.getPlayer().setVoteSkipThreshold(request.voteSkipThreshold());
            logMsg.append("VoteSkipThreshold=").append(request.voteSkipThreshold()).append(" ");
        }
        if (request.voteSkipWaitTime() != null) {
            voteSkipWaitTime.set(request.voteSkipWaitTime());
            appProperties.getPlayer().setVoteSkipWaitTime(request.voteSkipWaitTime());
            logMsg.append("VoteSkipWaitTime=").append(request.voteSkipWaitTime()).append("s ");
        }

        log.info(logMsg.toString().trim());
        
        // 关键增强：如果更新了投票相关配置，立即触发一次阈值检查
        if (isVoteSkipEnabled.get() && currentMusic.get() != null) {
            checkVoteSkipThreshold();
        }

        broadcastFullPlayerState();
    }

    public synchronized void togglePause(String sessionId) {
        if (!active || closed) return;
        reconcilePending();
        if (currentMusic.get() == null) {
            if (isLoading.get()) { cancelPlaybackAttempt(); isPaused.set(true); broadcastFullPlayerState(); return; }
            if (org.thornex.musicparty.room.RoomContext.current() != null) { isPaused.set(false); timestampAnchor.set(System.currentTimeMillis()); }
            if (pending != null || shouldPlayPrivateFmDj() || !queueManager.getQueueSnapshot().isEmpty()) {
                playNextInQueue();
            }
            if (org.thornex.musicparty.room.RoomContext.current() != null) broadcastFullPlayerState();
            return;
        }
        if (isRateLimited(sessionId)) return;

        // 锁定检查：如果是系统操作，放行。如果是用户操作，检查锁。
        // 规则：如果不控制播放权限（允许从暂停->播放），则只有当当前是播放状态(即试图暂停)且锁定时才拦截。
        if (!"SYSTEM".equals(sessionId)) {
            if (isPauseLocked.get() && !isPaused.get()) {
                // emit(...);
                return;
            }
        }

        // 核心：在切换状态的一瞬间，更新 Anchor
        // 1. 先计算出当前的进度
        long currentPos = calculateCurrentPosition();

        // 2. 更新状态
        boolean newState = !isPaused.get();
        isPaused.set(newState);

        // 3. 重置锚点：无论是暂停还是播放，当前进度都变成新的基准进度
        positionAnchor.set(currentPos);
        timestampAnchor.set(System.currentTimeMillis());

        log.info("Player {} by {}", newState ? "PAUSED" : "RESUMED", getUserName(sessionId));
        broadcastFullPlayerState();
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO, newState ? PlayerAction.PAUSE : PlayerAction.PLAY, getUserToken(sessionId), null));
    }

    public void cyclePlayMode(String sessionId) {
        if (isRateLimited(sessionId)) return;
        if (isPlayModeLocked.get() && !"SYSTEM".equals(sessionId)) return;

        PlayMode current;
        PlayMode next;
        do {
            current = playMode.get();
            next = switch (current) {
                case SEQUENTIAL -> PlayMode.SHUFFLE;
                case SHUFFLE -> PlayMode.REPEAT_ONE;
                case REPEAT_ONE -> PlayMode.SEQUENTIAL;
            };
        } while (!playMode.compareAndSet(current, next));

        // 同步 isShuffle 以保持向后兼容
        isShuffle.set(next == PlayMode.SHUFFLE);

        log.info("Play mode cycled to {} by {}", next, getUserName(sessionId));
        broadcastFullPlayerState();

        // 统一格式的通知：MessageFormatter 格式化为 "XXX 切换到了[模式]"
        String modeName = switch (next) {
            case SEQUENTIAL -> "顺序播放";
            case SHUFFLE -> "随机播放";
            case REPEAT_ONE -> "单曲循环";
        };
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                PlayerAction.MODE_CHANGE, getUserToken(sessionId), modeName));
    }

    public synchronized void resetSystem() {
        log.warn("!!!SYSTEM RESET INITIATED!!!");
        cancelPlaybackAttempt();
        pending = null;
        cancelEnqueues(null);
        currentMusic.set(null);
        positionAnchor.set(0);
        timestampAnchor.set(0);

        queueManager.clearAll();
        isPaused.set(org.thornex.musicparty.room.RoomContext.current() != null);
        isShuffle.set(false);
        playMode.set(PlayMode.SEQUENTIAL);
        isLoading.set(false);

        broadcastFullPlayerState();
        broadcastQueueUpdate();
        log.warn("System reset complete.");
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.RESET, "SYSTEM", null));
    }

    public void clearQueue() {
        cancelEnqueues(null);
        if (pending != null && pending.origin == PendingOrigin.QUEUE) { cancelPlaybackAttempt(); pending = null; }
        queueManager.clearPendingQueue();
        log.info("Queue cleared by Admin.");
        // 广播队列更新
        broadcastQueueUpdate();
        // 发送全员通知
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "管理员已清空播放列表"));
    }

    @EventListener
    public void handleDownloadEvent(DownloadStatusEvent event) {
        if (mailbox != null) { mailbox.submit(() -> handleDownloadEventNow(event)); return; }
        handleDownloadEventNow(event);
    }

    private void handleDownloadEventNow(DownloadStatusEvent event) {
        boolean existsInQueue = queueManager.getQueueSnapshot().stream()
                .anyMatch(item -> item.music().id().equals(event.getMusicId()));

        if (existsInQueue) {
            log.debug("Download status changed for {}, updating queue UI.", event.getMusicId());
            broadcastQueueUpdate();
            if (active && !isPaused.get() && currentMusic.get() == null) {
                playNextInQueue();
            }
        }
    }

    /**
     * 监听用户数量变化事件
     */
    @EventListener
    public void onUserCountChanged(UserCountChangeEvent event) {
        if (mailbox != null) { mailbox.submit(() -> onUserCountChangedNow(event)); return; }
        onUserCountChangedNow(event);
    }

    private void onUserCountChangedNow(UserCountChangeEvent event) {
        if (org.thornex.musicparty.room.RoomContext.current() == null && event.getOnlineUserCount() == 0 && !isStreamActive.get()) {
            enterIdleMode();
        }
        
        // 用户变动时重新检查投票阈值
        if (active && isVoteSkipEnabled.get() && currentMusic.get() != null) {
            checkVoteSkipThreshold();
            broadcastFullPlayerState();
        }
    }

    /**
     * 监听直播流状态变化
     */
    @EventListener
    public void onStreamStatusChanged(StreamStatusEvent event) {
        if (mailbox != null) { mailbox.submit(() -> onStreamStatusChangedNow(event)); return; }
        onStreamStatusChangedNow(event);
    }

    private void onStreamStatusChangedNow(StreamStatusEvent event) {
        boolean hasListeners = event.isHasListeners();
        this.isStreamActive.set(hasListeners);
        if (org.thornex.musicparty.room.RoomContext.current() != null) { broadcastFullPlayerState(); return; }
        log.info("System: Stream active status changed to: {}, Count: {}", hasListeners, event.getListenerCount());

        if (hasListeners) {
            // 场景 A: 列表为空，有人连入流 -> 尝试开始播放下一首
            if (currentMusic.get() == null) {
                playNextInQueue();
            } 
            // 场景 B: 正在暂停中，且网页端没人，有人连入流 -> 自动恢复播放
            else if (isPaused.get() && userService.getOnlineUserSummaries().isEmpty()) {
                log.info("System: Auto-resuming player for new stream listener.");
                togglePause("SYSTEM");
            }
        } else {
            // 场景 C: 流用户离开，且网页端也没人 -> 进入休眠
            // 额外校验确无流连接：并发的 addListener 可能在旧监听者断开后才登记新连接，
            // 此时陈旧的 StreamStatusEvent(false) 不应把播放器误送入休眠
            if (userService.getOnlineUserSummaries().isEmpty() && liveStreamService.getStreamConnectionCount() == 0) {
                enterIdleMode();
            }
        }
        broadcastFullPlayerState();
    }

    /**
     * 进入空闲模式，停止播放
     */
    private synchronized void enterIdleMode() {
        log.info("Last user disconnected. Entering idle mode.");
        cancelPlaybackAttempt();

        // 如果正在播放，自动暂停，记录当前进度
        if (currentMusic.get() != null && !isPaused.get()) {
            long currentPos = calculateCurrentPosition();

            if (isPaused.compareAndSet(false, true)) {
                // 暂停时，更新锚点为刚才计算出的准确进度
                positionAnchor.set(currentPos);
                timestampAnchor.set(System.currentTimeMillis()); // 这个时间在暂停期间主要用于超时判断

                log.info("Player paused as all users have disconnected. Position saved at: {}", currentPos);
                broadcastFullPlayerState();
            }
        }
    }

    public synchronized void pauseForEmptyRoom() {
        cancelPlaybackAttempt();
        positionAnchor.set(calculateCurrentPosition());
        timestampAnchor.set(System.currentTimeMillis());
        isPaused.set(true);
        isLoading.set(false);
        broadcastFullPlayerState();
    }

    /**
     * 定时清理长时间暂停的播放器状态
     */

    public synchronized void cleanupIdlePlayer() {
        if (!active || closed) return;
        if (isPaused.get() && currentMusic.get() != null) {
            // 在暂停状态下，timestampAnchor 记录的是暂停开始的时间
            long pausedDuration = System.currentTimeMillis() - timestampAnchor.get();
            if (pausedDuration > IDLE_RESET_TIMEOUT_MS) {
                log.info("Idle player timeout reached. Resetting now playing.");
                currentMusic.set(null);
                positionAnchor.set(0);
                timestampAnchor.set(0);
                isPaused.set(org.thornex.musicparty.room.RoomContext.current() != null);
                broadcastFullPlayerState();
            }
        }
    }

    /**
     * 周期状态广播（心跳）：让所有客户端周期性重锚播放进度，主动防漂移，
     * 同时让客户端能通过"长时间收不到广播"识别假连接。空闲（无曲且暂停）时跳过。
     */

    public void broadcastSyncHeartbeat() {
        if (currentMusic.get() == null && isPaused.get()) {
            return;
        }
        broadcastFullPlayerState();
    }

    // --- Broadcasting and Helper Methods ---

    public void broadcastQueueUpdate() {
        if (eventSink != null) markDirty(true);
        else eventPublisher.publishEvent(new QueueUpdateEvent(this, getQueueWithUpdatedStatus()));
    }

    public void broadcastFullPlayerState() {
        if (eventSink != null) markDirty(false);
        else eventPublisher.publishEvent(new PlayerStateEvent(this, getCurrentPlayerState()));
    }

    public void broadcastOnlineUsers() {
        // This is triggered by UserService, so we can keep it simple or create another event type
        broadcastFullPlayerState();
    }

    public void broadcastPasswordChanged() {
        emit(new SystemMessageEvent(this, SystemMessageEvent.Level.WARN, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "房间密码已更改，请重新验证"));
    }

    private void markDirty(boolean queue) {
        Runnable mark = () -> { if (queue) eventSink.markQueueDirty(MediaMode.MUSIC); else eventSink.markStateDirty(MediaMode.MUSIC); };
        if (mailbox != null && !mailbox.isOnMailboxThread()) mailbox.submit(mark); else mark.run();
    }

    private void emit(Object event) {
        if (eventSink == null) {
            if (event instanceof org.springframework.context.ApplicationEvent applicationEvent) eventPublisher.publishEvent(applicationEvent);
            else eventPublisher.publishEvent(event);
            return;
        }
        Runnable collect = () -> eventSink.emit(MediaMode.MUSIC, event);
        if (mailbox != null && !mailbox.isOnMailboxThread()) mailbox.submit(collect); else collect.run();
    }

    /** Called only after the switch's candidate snapshot has been saved successfully. */
    public synchronized void freezeForModeSwitch() {
        if (!active) return;
        positionAnchor.set(calculateCurrentPosition());
        long now = System.currentTimeMillis();
        frozenSeekRemaining = Math.max(0, seekAvailableAt - now);
        timestampAnchor.set(now);
        isPaused.set(true);
        cancelPlaybackAttempt();
        active = false;
        broadcastFullPlayerState();
    }

    public synchronized void activatePaused() {
        active = true;
        isPaused.set(true);
        long now = System.currentTimeMillis();
        timestampAnchor.set(now);
        seekAvailableAt = frozenSeekRemaining == 0 ? 0 : now + frozenSeekRemaining;
        Set<String> online = userService.getRecentlyActiveUserTokens();
        skipVotes.removeIf(token -> !online.contains(token));
        reconcilePending();
        broadcastFullPlayerState();
    }

    public synchronized void setActive(boolean value) { if (value) { if (!active) activatePaused(); } else freezeForModeSwitch(); }
    public boolean isActive() { return active && !closed; }
    public List<MusicQueueItem> getPersistenceQueueSnapshot() { return queueManager.getPersistenceQueueSnapshot(); }

    public synchronized void invalidateAsyncForClose() {
        closed = true;
        active = false;
        positionAnchor.set(calculateCurrentPosition());
        isPaused.set(true);
        cancelPlaybackAttempt();
        cancelEnqueues(null);
    }

    @jakarta.annotation.PreDestroy public synchronized void disposePlayback() {
        if (closed) return;
        closed = true; active = false;
        cancelPlaybackAttempt();
        for (EnqueueOperation operation : enqueueOperations) operation.cancel();
        enqueueOperations.clear();
    }

    public int clearUserQueue(String token) {
        cancelEnqueues(token);
        int removed = queueManager.removeByUser(token);
        reconcilePending();
        broadcastQueueUpdate();
        return removed;
    }

    private void cancelEnqueues(String token) {
        for (EnqueueOperation operation : new ArrayList<>(enqueueOperations)) {
            if (token == null || token.equals(operation.token)) { operation.cancel(); enqueueOperations.remove(operation); }
        }
        queueManager.cancelReservations(token);
    }

    private List<MusicQueueItem> getQueueWithUpdatedStatus() {
        return queueManager.getQueueSnapshot().stream().map(item -> {
            if ("netease".equals(item.music().platform())) {
                return item.status() == QueueItemStatus.READY ? item : item.withStatus(QueueItemStatus.READY);
            }
            if ("bilibili".equals(item.music().platform())) {
                CacheStatus cacheStatus = localCacheService.getStatus(item.music().id());
                QueueItemStatus newStatus = mapCacheStatusToEnum(cacheStatus);
                if (item.status() != newStatus) {
                    return item.withStatus(newStatus);
                }
            }
            return item;
        }).collect(Collectors.toList());
    }

    private Map<String, QueueItemStatus> buildStatusMap() {
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        for (MusicQueueItem item : queueManager.getQueueSnapshot()) {
            if ("bilibili".equals(item.music().platform())) {
                statusMap.put(item.music().id(), mapCacheStatusToEnum(localCacheService.getStatus(item.music().id())));
            } else {
                statusMap.put(item.music().id(), QueueItemStatus.READY);
            }
        }
        return statusMap;
    }

    private QueueItemStatus mapCacheStatusToEnum(CacheStatus status) {
        if (status == null) return QueueItemStatus.PENDING;
        return switch (status) {
            case COMPLETED -> QueueItemStatus.READY;
            case DOWNLOADING -> QueueItemStatus.DOWNLOADING;
            case FAILED -> QueueItemStatus.FAILED;
            default -> QueueItemStatus.PENDING;
        };
    }

    /*private void resetPauseState() {
        isPaused.set(false);
        pauseStateChangeTime.set(0);
        totalPausedTimeMillis.set(0);
    }*/

    private boolean isRateLimited(String userId) {
        if (controlLimiter != null) return !controlLimiter.tryAcquire();
        long now = System.currentTimeMillis();
        if (now - lastControlTimestamp.get() < GLOBAL_COOLDOWN_MS) {
            log.warn("Action rate limited for user: {}", userId);
            // emit(...); // Optional: notify user about rate limit
            return true;
        }
        lastControlTimestamp.set(now);
        return false;
    }

    public AppProperties getAppProperties() {
        return appProperties;
    }

    private IMusicApiService getApiService(String platform) {
        IMusicApiService service = apiServiceMap.get(platform);
        if (service == null) throw new ApiRequestException("Unsupported platform: " + platform);
        return service;
    }

    private String getUserToken(String sessionId) {
        if ("SYSTEM".equals(sessionId)) return "SYSTEM";
        return userService.getUser(sessionId).map(User::getToken).orElse("UNKNOWN_TOKEN");
    }

    private String getUserName(String sessionId) {
        return userService.getUser(sessionId).map(User::getName).orElse("Unknown User");
    }
}
