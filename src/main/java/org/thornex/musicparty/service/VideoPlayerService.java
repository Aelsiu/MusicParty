package org.thornex.musicparty.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.exception.ApiRequestException;
import org.thornex.musicparty.playback.PlaybackEventSink;
import org.thornex.musicparty.playback.PlaybackMailbox;
import org.thornex.musicparty.playback.PlaybackControlLimiter;
import org.thornex.musicparty.playback.VideoWaitTracker;
import org.thornex.musicparty.playback.VideoWaitAlarmScheduler;
import org.thornex.musicparty.room.RoomScoped;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.BilibiliVideoApiService;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/** Independent video timeline. Every mutation, including asynchronous commits, belongs to its room actor. */
@Service
@RoomScoped
public class VideoPlayerService {
    private final MusicQueueManager queue;
    private final UserService users;
    private final AppProperties properties;
    private final BilibiliVideoApiService api;
    private final BilibiliMusicApiService playlistApi;
    private final PlaybackMailbox mailbox;
    private final PlaybackEventSink sink;
    private final LongSupplier monotonicNanos;
    private final LongSupplier wallClock;
    private final VideoWaitTracker waits;
    private final VideoWaitAlarmScheduler alarmScheduler;
    private Disposable waitAlarm;
    private PlaybackMailbox.CompletionTicket waitAlarmTicket;
    private Long waitAlarmAt;
    private long waitAlarmVersion;
    private PlaybackControlLimiter controlLimiter;
    private final Map<String, VideoDescriptor> descriptors = new HashMap<>();
    private final Map<String, Admission> admissions = new LinkedHashMap<>();
    private final Set<String> votes = new HashSet<>();
    private VideoDescriptor currentDescriptor;
    private ResolvedVideoSource currentSource;
    private MusicQueueItem currentItem;
    private Pending pending;
    private SourceAttempt sourceAttempt;
    private boolean active;
    private boolean closed;
    private boolean paused = true;
    private double playbackRate = 1;
    private PlayMode playMode = PlayMode.SEQUENTIAL;
    private boolean fairShuffle = true;
    private boolean offlineShuffle;
    private boolean pauseLocked;
    private boolean skipLocked;
    private boolean modeLocked;
    private long positionAnchor;
    private long nanoAnchor;
    private long seekAvailableAt;
    private long frozenSeekRemaining;
    private String playbackId;
    private long sourceRevision;
    private long attemptVersion;
    private long cookieRevision;
    private long nextRefreshAllowedAt;
    private Long sourceRetryAt;
    private String sourceStatus = "EMPTY";
    private int sourceFailures;
    private boolean retryableSourceFailure;
    private long historyRevision;
    private List<Music> observedHistory = List.of();
    private String sourceErrorCode;
    private boolean policyRecoveryRequired;
    private long appliedPolicyRevision = -1;
    private String appliedCap = "UNLIMITED";
    private boolean currentDurationAdmitted;
    private boolean lastVoteGateOpen;

    @Autowired
    public VideoPlayerService(@Qualifier("videoQueueManager") MusicQueueManager queue, UserService users,
                              AppProperties properties, BilibiliVideoApiService api,
                              BilibiliMusicApiService playlistApi, PlaybackMailbox mailbox, PlaybackEventSink sink,
                              PlaybackControlLimiter controlLimiter) {
        this(queue, users, properties, api, playlistApi, mailbox, sink, System::nanoTime, System::currentTimeMillis);
        this.controlLimiter = controlLimiter;
    }

    public VideoPlayerService(MusicQueueManager queue, UserService users, AppProperties properties,
                              BilibiliVideoApiService api, BilibiliMusicApiService playlistApi,
                              PlaybackMailbox mailbox, PlaybackEventSink sink,
                              LongSupplier monotonicNanos, LongSupplier wallClock) {
        this(queue, users, properties, api, playlistApi, mailbox, sink, monotonicNanos, wallClock,
                VideoWaitAlarmScheduler.reactor());
    }

    public VideoPlayerService(MusicQueueManager queue, UserService users, AppProperties properties,
                              BilibiliVideoApiService api, BilibiliMusicApiService playlistApi,
                              PlaybackMailbox mailbox, PlaybackEventSink sink,
                              LongSupplier monotonicNanos, LongSupplier wallClock, VideoWaitAlarmScheduler alarmScheduler) {
        this.queue = queue;
        this.users = users;
        this.properties = properties;
        this.api = api;
        this.playlistApi = playlistApi;
        this.mailbox = mailbox;
        this.sink = sink;
        this.monotonicNanos = monotonicNanos;
        this.wallClock = wallClock;
        this.waits = new VideoWaitTracker(monotonicNanos, wallClock);
        this.alarmScheduler = alarmScheduler;
        this.controlLimiter = new PlaybackControlLimiter(monotonicNanos);
    }

    private void mutate() {
        mailbox.assertOnMailboxThread();
        if (closed) throw new PlaybackMailbox.RoomClosedException();
    }
    private void dirty() { sink.markStateDirty(MediaMode.VIDEO); }
    private void queueDirty() { sink.markQueueDirty(MediaMode.VIDEO); }
    private void notice(SystemMessageEvent.Level level, PlayerAction action, String token, String payload) {
        sink.emit(MediaMode.VIDEO, new SystemMessageEvent(this, level, action, token, payload));
    }
    private User member(String session) {
        User user = users.getUser(session).orElse(null);
        if (user == null || user.isGuest()) throw new ApiRequestException("请先设置昵称再操作视频");
        return user;
    }
    private String token(String session) { return "SYSTEM".equals(session) ? "SYSTEM" : member(session).getToken(); }

    public void enqueue(EnqueueRequest request, String session) {
        mutate();
        User user = member(session);
        if (request == null || !BilibiliVideoApiService.PLATFORM.equals(request.platform()))
            throw new ApiRequestException("VIDEO 模式只支持 bilibili-video");
        if (!properties.getBilibili().isEnabled()) throw new ApiRequestException("本房间 Bilibili 源已被禁用");
        String canonical = BilibiliVideoApiService.normalizeCanonicalId(request.musicId());
        if (logicalQueue().stream().anyMatch(item -> item.music().id().equals(canonical))
                || admissions.values().stream().anyMatch(op -> canonical.equals(op.bvid))) {
            throw new ApiRequestException("该视频已经在待播队列中");
        }
        Admission operation = reserveAdmission(user, 1, canonical);
        operation.subscription = api.getVideoDescriptor(canonical).subscribe(descriptor -> operation.ticket.commit(() -> {
            if (!finishAdmission(operation)) return;
            addDescriptor(descriptor, operation.user);
        }), error -> operation.ticket.commit(() -> {
            if (!finishAdmission(operation)) return;
            notice(SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, operation.user.token(), "添加失败：" + safeMessage(error));
        }));
    }

    public void enqueuePlaylist(EnqueuePlaylistRequest request, String session) {
        mutate();
        User user = member(session);
        if (request == null || !("bilibili".equals(request.platform()) || BilibiliVideoApiService.PLATFORM.equals(request.platform())))
            throw new ApiRequestException("视频只支持 Bilibili 收藏夹导入");
        if (!properties.getBilibili().isEnabled()) throw new ApiRequestException("本房间 Bilibili 源已被禁用");
        int limit = Math.min(properties.getPlayer().getMaxPlaylistImportSize(), remainingQuota(user.getToken()));
        if (limit <= 0) throw new ApiRequestException("视频队列或个人点播数量已达上限");
        Admission operation = reserveAdmission(user, limit, null);
        // Bilibili's existing query API caps each response at 20: explicitly paginate all accepted slots.
        operation.subscription = Flux.range(0, (limit + 19) / 20)
                .concatMap(page -> playlistApi.getPlaylistMusics(request.playlistId(), page * 20, 20))
                .flatMapIterable(items -> items).take(limit)
                .concatMap(music -> api.getVideoDescriptor(music.id()).map(descriptor -> new ImportItem(descriptor, null))
                        .onErrorResume(error -> Mono.just(new ImportItem(null, safeMessage(error)))))
                .collectList().subscribe(items -> operation.ticket.commit(() -> {
                    if (!finishAdmission(operation)) return;
                    int count = 0;
                    Map<String, Integer> reasons = new LinkedHashMap<>();
                    for (ImportItem item : items) {
                        if (item.descriptor != null && addDescriptor(item.descriptor, operation.user)) count++;
                        else reasons.merge(item.error == null ? "重复或队列已满" : item.error, 1, Integer::sum);
                    }
                    String reasonText = reasons.isEmpty() ? "" : "；原因：" + reasons;
                    notice(SystemMessageEvent.Level.SUCCESS, PlayerAction.SYSTEM_MESSAGE, operation.user.token(),
                            "收藏夹导入：成功 " + count + " 条，跳过 " + (items.size() - count) + " 条" + reasonText);
                }), error -> operation.ticket.commit(() -> {
                    if (!finishAdmission(operation)) return;
                    notice(SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, operation.user.token(), "导入失败：" + safeMessage(error));
                }));
    }

    private record ImportSlots(String token, MusicQueueManager.QueueReservation quota) {}
    private final Map<String,ImportSlots> importSlots = new HashMap<>();
    private java.util.function.Consumer<String> importCancellation = token -> {};
    public void setImportCancellation(java.util.function.Consumer<String> callback) { mutate(); importCancellation = callback; }
    public int availableImportSlots(String token) { mutate(); return remainingQuota(token); }
    public int getImportBatchLimit() { return Math.min(100,properties.getPlayer().getMaxPlaylistImportSize()); }
    public boolean isVideoSourceEnabled() { return properties.getBilibili().isEnabled(); }
    public String reserveImportSlots(String token,int count) {
        mutate(); if (count > remainingQuota(token)) throw new ApiRequestException("QUOTA_EXCEEDED");
        var reservation = queue.reserve(token,count);
        if (reservation.count() != count) { reservation.close(); throw new ApiRequestException("QUOTA_EXCEEDED"); }
        String id=UUID.randomUUID().toString(); importSlots.put(id,new ImportSlots(token,reservation)); return id;
    }
    public void releaseImportSlots(String id) { mutate(); var slots=importSlots.remove(id); if(slots!=null) slots.quota.close(); }
    public String commitImportedDescriptor(VideoDescriptor descriptor, UserSummary user) {
        mutate();
        if (!properties.getBilibili().isEnabled()) throw new ApiRequestException("PLATFORM_NOT_AVAILABLE");
        if (descriptor.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes()*60000L) throw new ApiRequestException("DURATION_EXCEEDED");
        if (logicalQueue().stream().anyMatch(item -> item.music().id().equals(descriptor.canonicalId()))) throw new ApiRequestException("DUPLICATE");
        if (!addDescriptor(descriptor,user)) throw new ApiRequestException("QUOTA_EXCEEDED");
        return queue.getQueueSnapshot().stream().filter(item -> item.music().id().equals(descriptor.canonicalId())).findFirst().orElseThrow().queueId();
    }
    private Admission reserveAdmission(User user, int count, String bvid) {
        if (count > remainingQuota(user.getToken())) throw new ApiRequestException("视频队列或个人点播数量已达上限");
        var ticket = mailbox.reserveCompletion();
        var queueReservation = queue.reserve(user.getToken(), count);
        if (queueReservation.count() != count) {
            ticket.cancel();
            queueReservation.close();
            throw new ApiRequestException("视频队列或个人点播数量已达上限");
        }
        Admission operation = new Admission(UUID.randomUUID().toString(),
                new UserSummary(user.getToken(), user.getSessionId(), user.getName(), false), count, bvid, ticket, queueReservation);
        admissions.put(operation.id, operation);
        return operation;
    }
    private int remainingQuota(String userToken) {
        List<MusicQueueItem> items = logicalQueue();
        int reserved = admissions.values().stream().mapToInt(op -> op.slots).sum() + importSlots.values().stream().mapToInt(op -> op.quota.count()).sum();
        int userReserved = admissions.values().stream().filter(op -> op.user.token().equals(userToken)).mapToInt(op -> op.slots).sum() + importSlots.values().stream().filter(op -> op.token.equals(userToken)).mapToInt(op -> op.quota.count()).sum();
        long userCount = items.stream().filter(item -> item.enqueuedBy().token().equals(userToken)).count();
        return Math.max(0, Math.min(properties.getQueue().getMaxSize() - items.size() - reserved,
                properties.getQueue().getMaxUserSongs() - (int) userCount - userReserved));
    }
    private boolean finishAdmission(Admission operation) {
        mutate();
        boolean existed = admissions.remove(operation.id, operation);
        return operation.quota.release() && existed;
    }
    private boolean addDescriptor(VideoDescriptor descriptor, UserSummary user) {
        if (descriptor.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes()*60000L) {
            notice(SystemMessageEvent.Level.ERROR,PlayerAction.SYSTEM_MESSAGE,user.token(),"添加失败：所选分 P 超过视频时长上限"); return false;
        }
        if (logicalQueue().stream().anyMatch(item -> item.music().id().equals(descriptor.canonicalId()))
                || logicalQueue().size() >= properties.getQueue().getMaxSize()
                || logicalQueue().stream().filter(item -> item.enqueuedBy().token().equals(user.token())).count()
                    >= properties.getQueue().getMaxUserSongs()) return false;
        MusicQueueItem item = queue.add(BilibiliVideoApiService.toMusic(descriptor), user, QueueItemStatus.READY);
        if (item == null) return false;
        descriptors.put(descriptor.canonicalId(), descriptor);
        queueDirty();
        notice(SystemMessageEvent.Level.SUCCESS, PlayerAction.ADD, user.token(), descriptor.title());
        return true;
    }

    public void togglePause(String session) {
        mutate();
        if (!"SYSTEM".equals(session) && !controlLimiter.tryAcquire()) return;
        if (pauseLocked && !paused && !"SYSTEM".equals(session)) return;
        String actor = token(session);
        if (!active) throw new ApiRequestException("视频模式尚未激活");
        if (!paused) {
            pauseKeepingScene();
            notice(SystemMessageEvent.Level.INFO, PlayerAction.PAUSE, actor, null);
            return;
        }
        if (policyRecoveryRequired) { paused = false; dirty(); return; }
        if (currentDescriptor != null && isSourceFresh()) {
            paused = false;
            nanoAnchor = monotonicNanos.getAsLong();
            sourceStatus = "READY";
            dirty();
            evaluateVotes();
        } else {
            nanoAnchor = monotonicNanos.getAsLong();
            paused = false;
            try { if (currentDescriptor != null) startSourceAttempt(currentDescriptor, true); else startPending(true); }
            catch (RuntimeException error) { paused = true; throw error; }
        }
        notice(SystemMessageEvent.Level.INFO, PlayerAction.RESUME, actor, null);
        armWaitAlarm();
    }

    private void blockPolicy() {
        positionAnchor = position(); nanoAnchor = monotonicNanos.getAsLong();
        currentSource = null; sourceStatus = "ERROR"; sourceErrorCode = "VIDEO_QUALITY_LIMIT";
        policyRecoveryRequired = true; sourceRetryAt = null; retryableSourceFailure = false;
        waits.cancel(); cancelWaitAlarm(); sourceRevision++; dirty();
    }
    public void applySourcePolicy() {
        if (!properties.getBilibili().isEnabled()) importCancellation.accept("ALL");
        var policy = properties.getPlayer().videoPolicySnapshot();
        if (appliedPolicyRevision == policy.revision() && Objects.equals(appliedCap,policy.maxResolution())) return;
        appliedPolicyRevision = policy.revision(); appliedCap = policy.maxResolution();
        if (currentSource == null) return;
        ResolvedVideoSource filtered = VideoSourcePolicy.filter(currentSource,appliedCap);
        if (Objects.equals(filtered,currentSource)) return;
        if (filtered == null) { cancelSource(); blockPolicy(); return; }
        long position = position(); currentSource = filtered; positionAnchor = position; nanoAnchor = monotonicNanos.getAsLong();
        sourceRevision++; waits.revise(positionAnchor,sourceRevision); armWaitAlarm(); dirty();
    }
    public void recoverSource(String session, String expectedPlaybackId, long revision, String queueId) {
        mutate(); member(session);
        if (!active || !policyRecoveryRequired || !Objects.equals(playbackId,expectedPlaybackId)
            || playbackId != null && sourceRevision != revision
            || playbackId == null && (pending == null || !Objects.equals(pending.item.queueId(),queueId)))
            throw new ApiRequestException("STALE_PLAYBACK_CONTEXT");
        if (!properties.getBilibili().isEnabled()) throw new ApiRequestException("PLATFORM_NOT_AVAILABLE");
        if (wallClock.getAsLong() < nextRefreshAllowedAt) throw new ApiRequestException("视频源重试冷却中，请稍后重试");
        startSourceAttempt(currentDescriptor,!paused,true);
    }
    private boolean isSourceFresh() {
        if (currentSource == null) return false;
        Instant now = Instant.ofEpochMilli(wallClock.getAsLong());
        boolean knownFresh = currentSource.expiresAt() == null || currentSource.expiresAt().isAfter(now.plusSeconds(60));
        boolean unknownFresh = !currentSource.hasUnknownExpiry() || currentSource.resolvedAt().isAfter(now.minusSeconds(300));
        return knownFresh && unknownFresh;
    }

    private void startPending(boolean resume) {
        if (sourceAttempt != null || !active || closed) return;
        if (pending == null) {
            List<MusicQueueItem> before = queue.getQueueSnapshot();
            Map<String, QueueItemStatus> statuses = new HashMap<>();
            before.forEach(item -> statuses.put(item.music().id(), QueueItemStatus.READY));
            MusicQueueItem item = queue.pollNext(playMode, fairShuffle, offlineShuffle, statuses, users.getRecentlyActiveUserTokens());
            if (item == null) { paused = true; sourceStatus = "EMPTY"; dirty(); return; }
            boolean queueOrigin = before.stream().anyMatch(existing -> existing.queueId().equals(item.queueId()));
            pending = new Pending(item, queueOrigin);
            if (queueOrigin) queue.retainPending(item);
            queueDirty();
        }
        VideoDescriptor descriptor = descriptors.get(pending.item.music().id());
        startSourceAttempt(descriptor, resume);
    }

    private void startSourceAttempt(VideoDescriptor knownDescriptor, boolean resume) {
        startSourceAttempt(knownDescriptor,resume,false);
    }
    private void startSourceAttempt(VideoDescriptor knownDescriptor, boolean resume, boolean recovery) {
        if (policyRecoveryRequired && !recovery) throw new ApiRequestException("POLICY_RECOVERY_REQUIRED");
        if (sourceAttempt != null) { dirty(); return; } // singleflight for the current instance/cookie revision
        long now = wallClock.getAsLong();
        if (sourceRetryAt != null && now < sourceRetryAt) {
            throw new ApiRequestException("视频源重试冷却中，请稍后重试");
        }
        PlaybackMailbox.CompletionTicket ticket = mailbox.reserveCompletion();
        SourceAttempt attempt = new SourceAttempt(++attemptVersion, cookieRevision, resume, currentDurationAdmitted && currentDescriptor != null, ticket);
        sourceAttempt = attempt;
        sourceStatus = "RESOLVING";
        nextRefreshAllowedAt = now + 5000;
        dirty();
        Mono<VideoDescriptor> descriptorMono = knownDescriptor != null ? Mono.just(knownDescriptor)
                : api.getVideoDescriptor(pending.item.music().id());
        attempt.subscription = descriptorMono.flatMap(descriptor -> {
            if (!attempt.admitted && descriptor.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes()*60000L)
                return Mono.error(new BilibiliVideoApiService.VideoSourceException(BilibiliVideoApiService.FailureCode.VIDEO_TOO_LONG,"所选分 P 超过视频时长上限"));
            Mono<ResolvedVideoSource> source = attempt.admitted ? api.resolveCurrentInstanceSource(descriptor) : api.resolveSource(descriptor);
            return source.map(value -> new SourceResult(descriptor,value));
        })
                .subscribe(result -> ticket.commit(() -> commitSource(attempt, result)),
                        error -> ticket.commit(() -> failSource(attempt, error)));
    }

    private boolean valid(SourceAttempt attempt) {
        return !closed && active && sourceAttempt == attempt && attempt.version == attemptVersion
                && attempt.cookieVersion == cookieRevision;
    }
    private void commitSource(SourceAttempt attempt, SourceResult result) {
        mutate();
        if (!valid(attempt)) return;
        if (!attempt.admitted && result.descriptor.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes()*60000L) {
            failSource(attempt,new BilibiliVideoApiService.VideoSourceException(BilibiliVideoApiService.FailureCode.VIDEO_TOO_LONG,"所选分 P 超过视频时长上限")); return;
        }
        long position = position(); // Preserve the room clock through a playing refresh.
        sourceAttempt = null;
        boolean initial = currentDescriptor == null;
        ResolvedVideoSource filtered = VideoSourcePolicy.filter(result.source,properties.getPlayer().getVideoMaxResolution());
        if (filtered == null) { blockPolicy(); return; }
        currentDescriptor = result.descriptor;
        currentSource = filtered;
        currentDurationAdmitted = true;
        policyRecoveryRequired = false; sourceErrorCode = null;
        descriptors.put(result.descriptor.canonicalId(), result.descriptor);
        if (initial) {
            currentItem = pending.item;
            if (pending.queueOrigin) queue.completePending(pending.item.queueId());
            pending = null;
            position = 0;
            playbackId = UUID.randomUUID().toString();
            waits.newPlayback(playbackId);
            sourceRevision = 0;
            seekAvailableAt = 0;
            votes.clear();
            queueDirty();
            sink.emit(MediaMode.VIDEO, new SystemMessageEvent(this, SystemMessageEvent.Level.INFO,
                    PlayerAction.PLAY_START, currentItem.enqueuedBy().token(), currentItem.enqueuedBy().name(), result.descriptor.title()));
        }
        sourceRevision++;
        positionAnchor = position;
        nanoAnchor = monotonicNanos.getAsLong();
        // Read the current intent. A seek or pause accepted during renewal must survive its commit.
        waits.revise(positionAnchor, sourceRevision);
        armWaitAlarm();
        sourceStatus = "READY";
        sourceFailures = 0;
        sourceRetryAt = null;
        retryableSourceFailure = false;
        dirty();
        if (!paused) evaluateVotes();
    }
    private void failSource(SourceAttempt attempt, Throwable error) {
        mutate();
        if (!valid(attempt)) return;
        sourceAttempt = null;
        if (error instanceof BilibiliVideoApiService.VideoSourceException policyError
            && policyError.getCode() == BilibiliVideoApiService.FailureCode.VIDEO_QUALITY_LIMIT) { blockPolicy(); return; }
        if (currentSource == null && !policyRecoveryRequired) paused = true;
        sourceStatus = "ERROR";
        sourceErrorCode = error instanceof BilibiliVideoApiService.VideoSourceException failure ? failure.getCode().name() : "TEMPORARY_SOURCE_FAILURE";
        retryableSourceFailure = !(error instanceof BilibiliVideoApiService.VideoSourceException failure) || failure.isRetryable();
        long delay = Math.min(60000, 5000L << Math.min(sourceFailures++, 4));
        sourceRetryAt = wallClock.getAsLong() + delay;
        if (pending != null) pending = new Pending(pending.item.withStatus(QueueItemStatus.FAILED), pending.queueOrigin);
        dirty();
        queueDirty();
        notice(SystemMessageEvent.Level.ERROR, PlayerAction.SYSTEM_MESSAGE, "SYSTEM", "视频源获取失败：" + safeMessage(error));
    }

    public void refreshSource(String expectedPlaybackId, long expectedSourceRevision, String reason) {
        mutate();
        if (policyRecoveryRequired) throw new ApiRequestException("POLICY_RECOVERY_REQUIRED");
        if (!active || currentDescriptor == null || !Objects.equals(playbackId, expectedPlaybackId))
            throw new ApiRequestException("视频播放实例已变化，请重新同步");
        if (expectedSourceRevision != sourceRevision) { dirty(); return; }
        if (sourceAttempt != null) { dirty(); return; }
        if (wallClock.getAsLong() < nextRefreshAllowedAt) {
            dirty();
            throw new ApiRequestException("视频源刷新冷却中，请稍后重试");
        }
        startSourceAttempt(currentDescriptor, false);
    }

    public void refreshCurrentSource(String session, String expectedPlaybackId, long expectedSourceRevision, String reason) {
        token(session);
        refreshSource(expectedPlaybackId, expectedSourceRevision, reason);
    }

    public void cookieChanged() {
        mutate();
        importCancellation.accept("ALL");
        if (policyRecoveryRequired) { cookieRevision++; cancelSource(); dirty(); return; }
        boolean failedCurrentSource = currentSource != null && (sourceRetryAt != null || "ERROR".equals(sourceStatus));
        cancelWaitAlarm();
        cookieRevision++;
        if (sourceAttempt != null) {
            cancelSource();
            sourceStatus = currentSource == null ? "ERROR" : "READY";
        }
        sourceRetryAt = null;
        retryableSourceFailure = false;
        nextRefreshAllowedAt = 0;
        if (failedCurrentSource) {
            // A user supplied new credentials after failure: retry this instance once under the new version.
            sourceRetryAt = wallClock.getAsLong();
            retryableSourceFailure = true;
        }
        dirty(); // A previously playing source keeps its room clock until refresh/resume.
        armWaitAlarm();
    }

    private void cancelSource() {
        attemptVersion++;
        SourceAttempt attempt = sourceAttempt;
        sourceAttempt = null;
        if (attempt != null) {
            attempt.ticket.cancel();
            if (attempt.subscription != null) attempt.subscription.dispose();
        }
    }
    private void pauseKeepingScene() {
        positionAnchor = position();
        nanoAnchor = monotonicNanos.getAsLong();
        paused = true;
        waits.cancel();
        cancelWaitAlarm();
        if (sourceAttempt != null && currentSource == null && !policyRecoveryRequired) cancelSource();
        sourceStatus = policyRecoveryRequired ? "ERROR" : currentSource != null ? "READY" : pending == null ? "EMPTY" : "ERROR";
        dirty();
    }
    public void freezeForModeSwitch() {
        mutate();
        frozenSeekRemaining = Math.max(0, seekAvailableAt - wallClock.getAsLong());
        pauseKeepingScene();
        cancelSource();
        policyRecoveryRequired = false; sourceErrorCode = null;
        active = false;
    }
    public void activatePaused() {
        mutate();
        active = true;
        paused = true;
        waits.cancel();
        cancelWaitAlarm();
        seekAvailableAt = frozenSeekRemaining == 0 ? 0 : wallClock.getAsLong() + frozenSeekRemaining;
        frozenSeekRemaining = 0;
        pruneVotes(); // Activation updates membership but deliberately does not evaluate a skip.
        dirty();
        queueDirty();
    }
    public void setActive(boolean value) { mutate(); active = value; if (!value) { waits.cancel(); cancelWaitAlarm(); } }
    public void pauseForEmptyRoom() { mutate(); pauseKeepingScene(); cancelSource(); }

    public void skipToNext(String session) {
        mutate();
        if (!"SYSTEM".equals(session) && !controlLimiter.tryAcquire()) return;
        if (skipLocked && !"SYSTEM".equals(session)) throw new ApiRequestException("跳过已锁定");
        String actor = token(session);
        if (!"SYSTEM".equals(session) && properties.getPlayer().isVoteSkipEnabled() && currentItem != null
                && !currentItem.enqueuedBy().token().equals(actor)) {
            if (!eligibleTokens().contains(actor)) throw new ApiRequestException("当前不具备投票资格");
            if (position() < properties.getPlayer().getVoteSkipWaitTime() * 1000L) throw new ApiRequestException("请等待播放至投票门槛");
            if (!votes.remove(actor)) votes.add(actor);
            evaluateVotes();
            dirty();
            return;
        }
        executeNext();
        notice(SystemMessageEvent.Level.INFO, PlayerAction.SKIP, actor, null);
    }
    private void executeNext() {
        cancelSource();
        cancelWaitAlarm();
        if (pending != null && pending.queueOrigin) queue.completePending(pending.item.queueId());
        pending = null;
        currentDurationAdmitted = false; policyRecoveryRequired = false; sourceErrorCode = null;
        currentDescriptor = null;
        currentSource = null;
        currentItem = null;
        positionAnchor = 0;
        playbackId = null;
        waits.newPlayback(null);
        sourceRevision = 0;
        sourceRetryAt = null;
        sourceFailures = 0;
        retryableSourceFailure = false;
        votes.clear();
        sourceStatus = "EMPTY";
        dirty();
        queueDirty();
        if (!paused && active) startPending(true);
    }
    public void tick() {
        mutate();
        applySourcePolicy();
        if (!active || currentDescriptor == null || policyRecoveryRequired) return;
        evaluateVotes();
        if (currentDescriptor == null) return;
        boolean voteGateOpen = position() >= properties.getPlayer().getVoteSkipWaitTime()*1000L;
        if (voteGateOpen != lastVoteGateOpen) { lastVoteGateOpen = voteGateOpen; dirty(); }
        long currentPosition = position();
        VideoWaitState previousWait = waits.snapshot();
        processWaitUpdate(waits.tick(!paused, currentPosition, sourceRevision), currentPosition, previousWait);
        if (paused || waits.isWaiting()) { maybeRenewSource(); return; }
        if (position() < currentDescriptor.durationMs()) { maybeRenewSource(); return; }
        if (playMode == PlayMode.REPEAT_ONE) {
            if (currentDescriptor.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes()*60000L) {
                positionAnchor = 0; nanoAnchor = monotonicNanos.getAsLong(); currentSource = null;
                paused = true; currentDurationAdmitted = false; sourceStatus = "ERROR"; sourceErrorCode = "VIDEO_TOO_LONG";
                cancelSource(); waits.cancel(); cancelWaitAlarm(); sourceRevision++; dirty(); return;
            }
            cancelSource();
            cancelWaitAlarm();
            positionAnchor = 0;
            nanoAnchor = monotonicNanos.getAsLong();
            playbackId = UUID.randomUUID().toString();
            waits.newPlayback(playbackId);
            seekAvailableAt = 0;
            votes.clear();
            if (!isSourceFresh()) startSourceAttempt(currentDescriptor, true);
            dirty();
            return;
        }
        queue.addToHistory(BilibiliVideoApiService.toMusic(currentDescriptor));
        executeNext();
    }
    public void playerLoop() { tick(); }

    /** Only the selected source's trusted expiry controls renewal; unknown deadlines use a bounded soft age. */
    private void maybeRenewSource() {
        if (policyRecoveryRequired || currentSource == null || sourceAttempt != null || users.getOnlineUserSummaries().isEmpty()) return;
        long now = wallClock.getAsLong();
        if (now < nextRefreshAllowedAt || sourceRetryAt != null && now < sourceRetryAt) return;
        // Credential/content failures await explicit action or a Cookie update instead of background polling.
        if (sourceRetryAt != null && !retryableSourceFailure) return;
        boolean due = currentSource.expiresAt() != null && now >= currentSource.expiresAt().toEpochMilli() - 60000
                || currentSource.hasUnknownExpiry() && now >= currentSource.resolvedAt().toEpochMilli() + 240000
                || sourceRetryAt != null && retryableSourceFailure;
        if (due) startSourceAttempt(currentDescriptor, false);
    }

    public double getPlaybackRate() { return playbackRate; }
    public String getPlayIntent() { return paused ? "PAUSED" : "PLAYING"; }
    public VideoWaitState getVideoWaitState() { return waits.snapshot(); }
    public VideoDescriptor getCurrentDescriptor() { return currentDescriptor; }
    public ResolvedVideoSource getCurrentSource() { return currentSource; }

    public void setPlaybackRate(double rate, String session, boolean manager) {
        mutate();
        if (!manager && !"SYSTEM".equals(session)) throw new ApiRequestException("仅 Owner / Root 可调整视频倍速");
        token(session);
        if (!Set.of(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).contains(rate))
            throw new ApiRequestException("不支持的视频倍速");
        if (playbackRate == rate) return;
        positionAnchor = position();
        nanoAnchor = monotonicNanos.getAsLong();
        playbackRate = rate;
        waits.revise(positionAnchor, sourceRevision);
        armWaitAlarm();
        dirty();
    }

    /** Selecting a trusted P creates an instance immediately and cannot inherit the old P's progress. */
    public void switchPage(int page, String session, boolean manager) {
        mutate();
        if (!manager && !"SYSTEM".equals(session)) throw new ApiRequestException("仅 Owner / Root 可切换当前视频分 P");
        token(session);
        if (!active || currentDescriptor == null) throw new ApiRequestException("当前没有可切换的视频");
        if (skipLocked) throw new ApiRequestException("请先解除跳过锁再切换分 P");
        if (page == currentDescriptor.page()) return;
        VideoPageDescriptor selected = currentDescriptor.pages().stream().filter(p -> p.page() == page)
                .findFirst().orElseThrow(() -> new ApiRequestException("分 P 不存在，请重新同步可信视频列表"));
        if (selected.durationMs() > properties.getPlayer().getVideoMaxDurationMinutes() * 60000L)
            throw new ApiRequestException("所选分 P 超过本房间视频时长上限");
        VideoDescriptor next = new VideoDescriptor(selected.canonicalId(), currentDescriptor.bvid(), selected.cid(), selected.page(),
                currentDescriptor.title(), selected.title(), currentDescriptor.ownerName(), selected.durationMs(),
                currentDescriptor.coverUrl(), currentDescriptor.pages());
        cancelSource();
        cancelWaitAlarm();
        currentDurationAdmitted = false; policyRecoveryRequired = false; sourceErrorCode = null;
        currentDescriptor = next;
        currentSource = null;
        currentItem = new MusicQueueItem(currentItem.queueId(), BilibiliVideoApiService.toMusic(next), currentItem.enqueuedBy(),
                QueueItemStatus.READY, currentItem.priority());
        descriptors.put(next.canonicalId(), next);
        playbackId = UUID.randomUUID().toString();
        sourceRevision = 0; positionAnchor = 0; nanoAnchor = monotonicNanos.getAsLong();
        seekAvailableAt = 0; votes.clear(); sourceRetryAt = null; sourceFailures = 0; nextRefreshAllowedAt = 0;
        retryableSourceFailure = false;
        waits.newPlayback(playbackId);
        startSourceAttempt(next, !paused);
        dirty();
    }

    public void watchStatus(String session, String verifiedWatchIdentity, String displayName, VideoWatchStatusRequest request) {
        mutate();
        if (!active || currentDescriptor == null || currentSource == null) return;
        // The coordinator has already verified room/epoch and the durable HttpOnly watcher identity.
        if (users.getUser(session).isEmpty()) return;
        long currentPosition = position();
        VideoWaitState previousWait = waits.snapshot();
        processWaitUpdate(waits.report(session, verifiedWatchIdentity, displayName, request,
                !paused, currentPosition, sourceRevision), currentPosition, previousWait);
    }

    private void processWaitUpdate(VideoWaitTracker.Update update, long currentPosition, VideoWaitState previousWait) {
        if (update.created()) {
            VideoWaitState state = waits.snapshot();
            // An INACTIVE report can create and remove a barrier in one mailbox operation.
            positionAnchor = state != null ? state.frozenPosition() : currentPosition;
            nanoAnchor = monotonicNanos.getAsLong();
        }
        if (update.ended()) nanoAnchor = monotonicNanos.getAsLong();
        update.timeouts().forEach(event -> sink.emit(MediaMode.VIDEO, event));
        // Lease heartbeats alone do not rebroadcast the large DASH source set to every member.
        if (update.created() || update.ended() || !Objects.equals(previousWait, waits.snapshot()) || !update.timeouts().isEmpty()) dirty();
        armWaitAlarm();
    }

    private void cancelWaitAlarm() {
        waitAlarmVersion++;
        if (waitAlarm != null) waitAlarm.dispose();
        if (waitAlarmTicket != null) waitAlarmTicket.cancel();
        waitAlarm = null; waitAlarmTicket = null; waitAlarmAt = null;
    }

    /** Exactly one bounded reservation/alarm per room, with the tracker owning all absolute deadlines. */
    private void armWaitAlarm() {
        Long due = active && !closed && !paused ? waits.nextCheckNanos(true) : null;
        if (Objects.equals(due, waitAlarmAt)) return;
        cancelWaitAlarm();
        if (due == null) return;
        PlaybackMailbox.CompletionTicket ticket;
        try { ticket = mailbox.reserveCompletion(); }
        catch (PlaybackMailbox.RoomBusyException | PlaybackMailbox.RoomClosedException unavailable) { return; }
        long version = waitAlarmVersion;
        String instance = playbackId;
        waitAlarmAt = due; waitAlarmTicket = ticket;
        waitAlarm = alarmScheduler.schedule(due - monotonicNanos.getAsLong(), () -> ticket.commit(() -> {
            if (closed || !active || paused || version != waitAlarmVersion || !Objects.equals(instance, playbackId)) return;
            waitAlarm = null; waitAlarmTicket = null; waitAlarmAt = null;
            long position = position();
            VideoWaitState previous = waits.snapshot();
            processWaitUpdate(waits.tick(true, position, sourceRevision), position, previous);
        }));
    }

    public boolean seek(SeekRequest request, String session, boolean manager) {
        mutate();
        String actor = token(session);
        String policy = properties.getPlayer().getSeekPolicy();
        boolean allowed = "ALL".equals(policy) || ("OWNER_AND_ENQUEUER".equals(policy)
                && (manager || (currentItem != null && currentItem.enqueuedBy().token().equals(actor))));
        if (!allowed) throw new ApiRequestException("本房间不允许你跳转播放进度");
        if (request == null || currentDescriptor == null || currentSource == null || !Objects.equals(playbackId, request.playbackId()))
            throw new ApiRequestException("视频已变化或正在解析，请等待播放状态更新");
        if (request.position() == null || request.position() < 0 || request.position() >= currentDescriptor.durationMs())
            throw new ApiRequestException("跳转位置超出视频时长");
        if (wallClock.getAsLong() < seekAvailableAt) throw new ApiRequestException("房间跳转冷却中，请稍后再试");
        positionAnchor = request.position();
        nanoAnchor = monotonicNanos.getAsLong();
        seekAvailableAt = wallClock.getAsLong() + 3000;
        waits.revise(positionAnchor, sourceRevision);
        armWaitAlarm();
        evaluateVotes();
        dirty();
        notice(SystemMessageEvent.Level.INFO, PlayerAction.JUMP, actor, null);
        return true;
    }
    public void cyclePlayMode(String session) {
        mutate();
        if (!"SYSTEM".equals(session) && !controlLimiter.tryAcquire()) return;
        if (modeLocked && !"SYSTEM".equals(session)) return;
        playMode = switch (playMode) { case SEQUENTIAL -> PlayMode.SHUFFLE; case SHUFFLE -> PlayMode.REPEAT_ONE; case REPEAT_ONE -> PlayMode.SEQUENTIAL; };
        dirty();
        notice(SystemMessageEvent.Level.INFO, PlayerAction.MODE_CHANGE, token(session), switch (playMode) {
            case SEQUENTIAL -> "顺序播放"; case SHUFFLE -> "随机播放"; case REPEAT_ONE -> "单条循环";
        });
    }
    public void toggleFairShuffle(String session) { mutate(); token(session); if (!"SYSTEM".equals(session) && !controlLimiter.tryAcquire()) return; fairShuffle = !fairShuffle; dirty(); }
    public void toggleAllowOfflineShuffle(String session) { mutate(); token(session); if (!"SYSTEM".equals(session) && !controlLimiter.tryAcquire()) return; offlineShuffle = !offlineShuffle; dirty(); }
    public void setLock(String type, boolean value) {
        mutate();
        switch (type.toUpperCase(Locale.ROOT)) {
            case "PAUSE" -> pauseLocked = value;
            case "SKIP" -> skipLocked = value;
            case "SHUFFLE" -> modeLocked = value;
            default -> throw new ApiRequestException("未知播放锁类型");
        }
        evaluateVotes();
        dirty();
    }
    public void setAllLocks(boolean value) { mutate(); pauseLocked = value; skipLocked = value; modeLocked = value; evaluateVotes(); dirty(); }

    public void topSong(String queueId, String session) {
        mutate();
        String actor = token(session);
        if (pending != null && pending.queueOrigin && pending.item.queueId().equals(queueId)) throw new ApiRequestException("已进入播放准备");
        TopResult result = queue.top(queueId, playMode);
        if (result == TopResult.NONE) throw new ApiRequestException("条目不存在或已经全局置顶");
        if (result != TopResult.NONE) {
            queueDirty();
            if (result == TopResult.GLOBAL) notice(SystemMessageEvent.Level.INFO, PlayerAction.TOP, actor,
                    queue.getItem(queueId).map(item -> item.music().name()).orElse("视频"));
        }
    }
    public void removeSongFromQueue(String queueId, String session) { removeSongFromQueue(queueId, session, false); }
    public void removeSongFromQueue(String queueId, String session, boolean manager) {
        mutate();
        User user = member(session);
        if (pending != null && pending.queueOrigin && pending.item.queueId().equals(queueId)) {
            if (!manager && !pending.item.enqueuedBy().token().equals(user.getToken())) return;
            cancelSource();
            queue.completePending(pending.item.queueId());
            pending = null;
            policyRecoveryRequired = false; sourceErrorCode = null;
            paused = true;
            sourceStatus = "EMPTY";
            queueDirty();
            return;
        }
        queue.removeAuthorized(queueId, user.getToken(), manager).ifPresent(item -> {
            queueDirty();
            notice(SystemMessageEvent.Level.INFO, PlayerAction.REMOVE, user.getToken(), item.music().name());
        });
    }
    private void cancelAdmissions(java.util.function.Predicate<Admission> filter) {
        for (Admission operation : new ArrayList<>(admissions.values())) {
            if (!filter.test(operation)) continue;
            admissions.remove(operation.id);
            operation.quota.close();
            operation.ticket.cancel();
            if (operation.subscription != null) operation.subscription.dispose();
        }
    }
    public void clearQueue() {
        mutate();
        importCancellation.accept("ALL");
        cancelAdmissions(op -> true);
        if (pending != null && pending.queueOrigin) { cancelSource(); queue.completePending(pending.item.queueId()); pending = null; paused = true; sourceStatus = "EMPTY"; policyRecoveryRequired = false; sourceErrorCode = null; }
        queue.clearPendingQueue();
        queueDirty();
    }
    public int clearUserSongs(String userToken) {
        mutate();
        importCancellation.accept(userToken);
        cancelAdmissions(op -> op.user.token().equals(userToken));
        int count = queue.removeByUser(userToken);
        if (pending != null && pending.queueOrigin && pending.item.enqueuedBy().token().equals(userToken)) {
            cancelSource(); queue.completePending(pending.item.queueId()); pending = null; paused = true; sourceStatus = "EMPTY"; policyRecoveryRequired = false; sourceErrorCode = null;
        }
        queueDirty();
        return count;
    }
    public int clearOfflineSongs() {
        mutate();
        Set<String> online = users.getRecentlyActiveUserTokens();
        Set<String> offline = new HashSet<>();
        logicalQueue().stream().map(item -> item.enqueuedBy().token()).filter(token -> !online.contains(token)).forEach(offline::add);
        admissions.values().stream().map(op -> op.user.token()).filter(token -> !online.contains(token)).forEach(offline::add);
        importSlots.values().stream().map(op -> op.token).filter(token -> !online.contains(token)).forEach(offline::add);
        return offline.stream().mapToInt(this::clearUserSongs).sum();
    }
    public int clearUserQueue(String userToken) { return clearUserSongs(userToken); }
    public void resetSystem() {
        mutate();
        clearQueue();
        cancelSource();
        cancelWaitAlarm();
        queue.clearAll();
        observedHistory = List.of(); historyRevision++;
        descriptors.clear();
        currentDescriptor = null; currentSource = null; currentItem = null; pending = null;
        playbackId = null; sourceRevision = 0; positionAnchor = 0; paused = true; policyRecoveryRequired = false; sourceErrorCode = null; currentDurationAdmitted = false;
        waits.newPlayback(null); playbackRate = 1;
        playMode = PlayMode.SEQUENTIAL; sourceStatus = "EMPTY"; sourceRetryAt = null;
        sourceFailures = 0; retryableSourceFailure = false; votes.clear();
        queueDirty();
    }

    public SettingsSnapshot.PlayerSettings getPlayerSettings() {
        return new SettingsSnapshot.PlayerSettings(playMode.name(), fairShuffle, offlineShuffle,
                null, null, null, pauseLocked, skipLocked, modeLocked, playbackRate);
    }
    public void applyPlayerSettings(SettingsSnapshot.PlayerSettings settings) {
        mutate();
        if (settings == null) return;
        if (settings.playMode() != null) try { playMode = PlayMode.valueOf(settings.playMode()); } catch (IllegalArgumentException ignored) { playMode = PlayMode.SEQUENTIAL; }
        if (settings.fairShuffle() != null) fairShuffle = settings.fairShuffle();
        if (settings.allowOfflineShuffle() != null) offlineShuffle = settings.allowOfflineShuffle();
        if (settings.pauseLocked() != null) pauseLocked = settings.pauseLocked();
        if (settings.skipLocked() != null) skipLocked = settings.skipLocked();
        if (settings.playModeLocked() != null) modeLocked = settings.playModeLocked();
        playbackRate = settings.playbackRate() != null
                && Set.of(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0).contains(settings.playbackRate()) ? settings.playbackRate() : 1;
        paused = true;
        cancelWaitAlarm();
    }
    public List<MusicQueueItem> getPersistenceQueueSnapshot() {
        return logicalQueue().stream().map(item -> item.withStatus(QueueItemStatus.READY)).toList();
    }
    public List<Music> getHistorySnapshot() {
        List<Music> snapshot = List.copyOf(queue.getHistorySnapshot());
        if (!snapshot.equals(observedHistory)) { observedHistory = snapshot; historyRevision++; }
        return snapshot;
    }
    public long getHistoryRevision() { getHistorySnapshot(); return historyRevision; }
    public Map<String,Object> interaction(String session) {
        User user = users.getUser(session).orElse(null);
        String reason = !active || currentItem == null ? "INACTIVE" : user == null || user.isGuest() ? "GUEST"
            : currentItem.enqueuedBy().token().equals(user.getToken()) ? "ENQUEUER" : skipLocked ? "LOCKED"
            : !properties.getPlayer().isVoteSkipEnabled() ? "DISABLED"
            : !eligibleTokens().contains(user.getToken()) ? "INACTIVE"
            : position() < properties.getPlayer().getVoteSkipWaitTime()*1000L ? "WAITING" : "ELIGIBLE";
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("playbackId",playbackId); state.put("canVote","ELIGIBLE".equals(reason));
        state.put("hasVoted",user != null && votes.contains(user.getToken()));
        state.put("voteWaitRemainingMs",Math.max(0,properties.getPlayer().getVoteSkipWaitTime()*1000L-position()));
        state.put("reason",reason); return state;
    }

    public void trimHistoryToLimit() { mutate(); queue.trimHistoryToLimit(); queueDirty(); }
    public void restore(List<MusicQueueItem> restoredQueue, List<Music> history) {
        mutate();
        queue.restore(restoredQueue == null ? List.of() : restoredQueue.stream()
                .filter(item -> BilibiliVideoApiService.PLATFORM.equals(item.music().platform()))
                .map(item -> item.withStatus(QueueItemStatus.READY)).toList(), history == null ? List.of() : history);
        observedHistory = List.copyOf(queue.getHistorySnapshot()); historyRevision++;
        paused = true;
        cancelWaitAlarm();
    }
    private List<MusicQueueItem> logicalQueue() {
        List<MusicQueueItem> result = new ArrayList<>();
        if (pending != null && pending.queueOrigin) result.add(pending.item);
        for (MusicQueueItem item : queue.getQueueSnapshot()) {
            if (result.stream().noneMatch(existing -> existing.queueId().equals(item.queueId()))) result.add(item);
        }
        return result;
    }
    private long position() {
        if (currentDescriptor == null) return 0;
        double elapsed = paused || policyRecoveryRequired || waits.isWaiting() || currentSource == null ? 0
                : Math.max(0, (monotonicNanos.getAsLong() - nanoAnchor) / 1_000_000.0) * playbackRate;
        return Math.min(currentDescriptor.durationMs(), positionAnchor + (long) elapsed);
    }
    private void pruneVotes() {
        Set<String> eligible = eligibleTokens();
        votes.retainAll(eligible);
    }
    private Set<String> eligibleTokens() {
        Set<String> online = users.getRecentlyActiveUserTokens();
        Set<String> eligible = new HashSet<>();
        for (UserSummary user : users.getOnlineUserSummaries()) {
            if (!user.isGuest() && online.contains(user.token())
                    && (currentItem == null || !user.token().equals(currentItem.enqueuedBy().token()))) eligible.add(user.token());
        }
        return eligible;
    }
    private void evaluateVotes() {
        if (!active || skipLocked || currentDescriptor == null || !properties.getPlayer().isVoteSkipEnabled()
                || position() < properties.getPlayer().getVoteSkipWaitTime() * 1000L) return;
        pruneVotes();
        int eligible = eligibleTokens().size();
        if (eligible > 0 && (double) votes.size() / eligible >= properties.getPlayer().getVoteSkipThreshold()) executeNext();
    }
    public void membersChanged() {
        mutate();
        for (String session : waits.sessions()) if (users.getUser(session).isEmpty()) waits.disconnect(session);
        long currentPosition = position();
        VideoWaitState previousWait = waits.snapshot();
        processWaitUpdate(waits.tick(!paused, currentPosition, sourceRevision), currentPosition, previousWait);
        if (active) { pruneVotes(); evaluateVotes(); }
        dirty();
    }
    public String getSourceStatus() { return sourceStatus; }
    public Long getSourceRetryAt() { return sourceRetryAt != null ? sourceRetryAt : wallClock.getAsLong() < nextRefreshAllowedAt ? nextRefreshAllowedAt : null; }

    public PlayerState getCurrentPlayerState() {
        applySourcePolicy();
        long serverTime = wallClock.getAsLong();
        PlayableMusic media = currentDescriptor == null ? null : currentSource == null
                ? new PlayableMusic(currentDescriptor.canonicalId(), currentDescriptor.title(), List.of(currentDescriptor.ownerName()),
                    currentDescriptor.durationMs(), BilibiliVideoApiService.PLATFORM, null, currentDescriptor.coverUrl(), false)
                : BilibiliVideoApiService.toPlayableMusic(currentDescriptor, currentSource);
        NowPlayingInfo info = currentDescriptor == null || currentItem == null ? null : new NowPlayingInfo(
                media, position(),
                currentItem.enqueuedBy().token(), currentItem.enqueuedBy().name(), Set.of(), List.of(), playbackId,
                seekAvailableAt, serverTime, sourceRevision,
                currentSource == null || currentSource.expiresAt() == null ? null : currentSource.expiresAt().toEpochMilli(), currentSource, currentDescriptor);
        var p = properties.getPlayer();
        var policy = p.videoPolicySnapshot();
        PlayerState state = new PlayerState(info, List.copyOf(logicalQueue()), playMode.name(), playMode == PlayMode.SHUFFLE,
                fairShuffle, offlineShuffle, users.getOnlineUserSummaries(), paused || waits.isWaiting() || currentSource == null,
                pauseLocked, skipLocked, modeLocked,
                sourceAttempt != null && currentSource == null, 0, false, p.isVoteSkipEnabled(), p.getVoteSkipThreshold(), p.getVoteSkipWaitTime(),
                (int) votes.stream().filter(eligibleTokens()::contains).count(), eligibleTokens().size(),
                new PlayerState.AppConfigSummary(properties.getQueue().getMaxSize(), properties.getQueue().getHistorySize(),
                        properties.getQueue().getMaxUserSongs(), p.getMaxPlaylistImportSize(), properties.getChat().getMaxHistorySize(),
                        properties.getChat().getMinIntervalMs(), properties.getChat().getMaxMessageLength(), properties.getNetease().getQuality(),
                        properties.getNetease().isEnabled(), properties.getBilibili().isEnabled(), properties.getBilibili().getMaxDurationMinutes(),
                        p.isVoteSkipEnabled(), p.getVoteSkipThreshold(), p.getVoteSkipWaitTime(),
                        properties.getNetease().getCookie() != null && !properties.getNetease().getCookie().isBlank(),
                        new PlayerState.AppConfigSummary.PrivateDjConfigSummary(properties.getPrivateDj().getMode(),
                                properties.getPrivateDj().isFillBlankEnabled(), properties.getPrivateDj().isJoinQueueEnabled(), properties.getPrivateDj().isCustodyEnabled()),
                        p.getSeekPolicy(), policy.maxDurationMinutes(), api.isCookieConfigured(), policy.maxResolution(), policy.revision()));
        return state.versioned(null, "VIDEO", 0, 0, 0, serverTime, sourceStatus, getSourceRetryAt())
                .withVideoState(getPlayIntent(), playbackRate, getVideoWaitState())
                .withP2(sourceErrorCode,policyRecoveryRequired,pending == null ? null : new PlayerState.VideoPending(
                    pending.item.queueId(),pending.item.music().id(),pending.queueOrigin ? "QUEUE" : "HISTORY"),getHistoryRevision());
    }

    public void close() {
        mailbox.assertOnMailboxThread();
        if (closed) return;
        importCancellation.accept("ALL");
        pauseKeepingScene();
        cancelSource();
        cancelAdmissions(op -> true);
        active = false;
        closed = true;
    }
    public void invalidateAsyncForClose() { close(); }
    private static String safeMessage(Throwable error) {
        return error instanceof BilibiliVideoApiService.VideoSourceException ? error.getMessage()
                : "请求失败，请检查本房间 Cookie、视频支持范围和服务器网络后重试";
    }
    private record Pending(MusicQueueItem item, boolean queueOrigin) {}
    private record ImportItem(VideoDescriptor descriptor, String error) {}
    private record SourceResult(VideoDescriptor descriptor, ResolvedVideoSource source) {}
    private static final class Admission {
        final String id; final UserSummary user; final int slots; final String bvid;
        final PlaybackMailbox.CompletionTicket ticket; final MusicQueueManager.QueueReservation quota; Disposable subscription;
        Admission(String id, UserSummary user, int slots, String bvid, PlaybackMailbox.CompletionTicket ticket, MusicQueueManager.QueueReservation quota) {
            this.id = id; this.user = user; this.slots = slots; this.bvid = bvid; this.ticket = ticket; this.quota = quota;
        }
    }
    private static final class SourceAttempt {
        final long version; final long cookieVersion; final boolean resume; final boolean admitted;
        final PlaybackMailbox.CompletionTicket ticket; Disposable subscription;
        SourceAttempt(long version, long cookieVersion, boolean resume, boolean admitted, PlaybackMailbox.CompletionTicket ticket) {
            this.version = version; this.cookieVersion = cookieVersion; this.resume = resume; this.admitted = admitted; this.ticket = ticket;
        }
    }
}
