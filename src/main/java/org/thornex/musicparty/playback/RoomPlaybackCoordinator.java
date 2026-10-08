package org.thornex.musicparty.playback;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.room.*;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.*;
import java.util.concurrent.*;

/** The only public playback boundary. Engines and callbacks execute in this room's bounded actor. */
@Service @RoomScoped @RequiredArgsConstructor @Slf4j
public class RoomPlaybackCoordinator {
    private final PlaybackMailbox mailbox;
    private final PlaybackEventSink sink;
    private final MusicPlayerService music;
    private final VideoPlayerService video;
    private final QueuePersistenceService persistence;
    private final UserService users;
    private final RoomAccessService access;
    private final LiveStreamService stream;
    private final SimpMessagingTemplate messaging;
    private final ApplicationEventPublisher publisher;
    private final String playbackEpoch = UUID.randomUUID().toString();
    private final Object bootstrapMonitor = new Object();
    private CompletableFuture<Void> bootstrap;
    private MediaMode mode = MediaMode.MUSIC;
    private long modeEpoch, stateRevision, queueRevision;
    private boolean ready, closed, deleted;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.thornex.musicparty.service.api.BilibiliVideoFavoritesService favorites;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.thornex.musicparty.service.api.VideoFavoriteMetadataResolver favoriteResolver;
    private final VideoPreviewRegistry previews = new VideoPreviewRegistry();
    private final VideoImportRegistry imports = new VideoImportRegistry();
    private final Map<VideoImportRegistry.Entry,ImportWork> importWork = new HashMap<>();
    private final Map<String,Map<String,Object>> interactionStates = new HashMap<>();
    private long interactionRevision;
    private static final class ImportWork {
        final PlaybackMailbox.CompletionTicket ticket;
        final String reservation;
        reactor.core.Disposable subscription;
        ImportWork(PlaybackMailbox.CompletionTicket ticket,String reservation) { this.ticket=ticket;this.reservation=reservation; }
    }
    private record Result(String operation, JsonNode arguments, Map<String,Object> value) {}
    private final LinkedHashMap<String,Result> results = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingTickFlags = new java.util.concurrent.atomic.AtomicInteger();
    private static final com.fasterxml.jackson.databind.ObjectMapper WATCH_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Capability observations are independent of named-member playback controls. */
    public CompletableFuture<Void> watchStatus(String session, JsonNode body) {
        start();
        return mailbox.submit(() -> {
            requireOpen();
            RoomAccessService.Connection connection = access.connection(session);
            if (!RoomContext.require().equals(connection.roomId()) || connection.watchIdentity() == null
                    || !playbackEpoch.equals(text(body,"expectedPlaybackEpoch")) || mode != MediaMode.VIDEO
                    || !mode.name().equals(text(body,"expectedMode")) || body == null
                    || !body.path("expectedModeEpoch").canConvertToLong()
                    || body.path("expectedModeEpoch").longValue() != modeEpoch) return;
            User user = users.getUser(session).orElse(null);
            if (user == null) return;
            try {
                VideoWatchStatusRequest report = WATCH_MAPPER.treeToValue(body,VideoWatchStatusRequest.class);
                video.watchStatus(session,connection.watchIdentity(),user.isGuest() ? "游客" : user.getName(),report);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalidReport) {
                // Invalid observations cannot alter controls, anchors, or another connection's eligibility.
            }
        });
    }

    public void start() {
        synchronized (bootstrapMonitor) {
            if (bootstrap != null) return;
            bootstrap = mailbox.submit(() -> {
                persistence.ensureLoaded();
                mode = "VIDEO".equals(persistence.restoredMode()) ? MediaMode.VIDEO : MediaMode.MUSIC;
                music.setActive(mode == MediaMode.MUSIC);
                video.setActive(mode == MediaMode.VIDEO);
                if (mode == MediaMode.MUSIC) music.activatePaused(); else video.activatePaused();
                stream.setPlaybackMode(mode == MediaMode.MUSIC);
                ready = true;
                video.setImportCancellation(this::cancelImportsForUser);
                mailbox.setAfterTask(this::flush);
                sink.markQueueDirty(mode);
            });
        }
    }

    public void ensureReady() { start(); await(bootstrap); }

    /** CONNECT waits outside RoomScope construction/engine locks until bootstrap and member registration have committed. */
    public UserSummary joinMember(String sessionId, String token, String name) {
        start();
        return await(mailbox.submit(() -> {
            requireOpen(); requireMemberRoom(sessionId);
            User user = users.getUser(sessionId).orElse(null);
            if (user == null) {
                user = users.handleConnect(sessionId,token,name);
                try { requireMemberRoom(sessionId); }
                catch (RuntimeException disconnected) { users.disconnectUser(sessionId); throw disconnected; }
                membersChanged();
            }
            return new UserSummary(user.getToken(),sessionId,user.getName(),user.isGuest());
        }));
    }

    /** Read identity/profile after all accepted room work, including the CONNECT registration barrier. */
    public <T> T readMember(String sessionId, Callable<T> read) {
        start();
        return await(mailbox.submit(() -> { requireOpen(); requireMemberRoom(sessionId); return read.call(); }));
    }

    private void requireMemberRoom(String sessionId) {
        if (!RoomContext.require().equals(access.connection(sessionId).roomId())) throw new IllegalArgumentException("ACCESS_DENIED");
    }

    public CompletableFuture<Void> internal(Runnable task) {
        start();
        return mailbox.submit(() -> { requireOpen(); task.run(); });
    }

    public CompletableFuture<Map<String,Object>> command(String sessionId, String operation, JsonNode body) {
        start();
        CompletableFuture<Map<String,Object>> result = mailbox.submit(() -> checked("stomp:" + sessionId,
                sessionId, null, operation, body, null));
        result.whenComplete((value,error) -> {
            if (error != null) privateError(sessionId, errorCode(error), "房间繁忙，请稍后重试");
        });
        return result;
    }

    public CompletableFuture<Map<String,Object>> chat(String sessionId, JsonNode body, Runnable action) {
        start();
        return mailbox.submit(() -> checked("stomp:" + sessionId, sessionId, null, "chat", body, action));
    }

    public Map<String,Object> http(String managerToken, String protocol, String operation, JsonNode body) {
        if (!"3".equals(protocol)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "CLIENT_UPGRADE_REQUIRED");
        start();
        // Hash the verified session identifier. Raw authorization credentials are never cache keys or logs.
        String scope = "http:" + sessionIdentity(managerToken);
        return await(mailbox.submit(() -> checked(scope, null, managerToken, operation, body, null)));
    }

    public <T> T manage(String managerToken, Callable<T> action) {
        start();
        return await(mailbox.submit(() -> {
            requireOpen(); access.own(managerToken, RoomContext.require()); return action.call();
        }));
    }

    public Map<String,Object> httpAction(String managerToken, String protocol, String operation, JsonNode body, Runnable action) {
        if (!"3".equals(protocol)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"CLIENT_UPGRADE_REQUIRED");
        start();
        return await(mailbox.submit(() -> checked("http:" + sessionIdentity(managerToken), null, managerToken, operation, body, action)));
    }

    private String sessionIdentity(String token) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Map<String,Object> checked(String identity, String sessionId, String managerToken,
            String operation, JsonNode body, Runnable action) {
        requireOpen();
        boolean manager;
        if (sessionId != null) {
            RoomAccessService.Connection connection = access.connection(sessionId);
            if (!RoomContext.require().equals(connection.roomId())) throw new IllegalArgumentException("ACCESS_DENIED");
            if (users.getUser(sessionId).map(User::isGuest).orElse(true)) return reject(sessionId,"NAMED_MEMBER_REQUIRED");
            manager = connection.managerToken() != null && !connection.managerToken().isBlank();
            if (manager) access.own(connection.managerToken(), RoomContext.require());
        } else { access.own(managerToken, RoomContext.require()); manager = true; }
        String requestId = text(body, "requestId");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) return reject(sessionId,"INVALID_REQUEST_ID");
        String key = playbackEpoch + ":" + identity + ":" + requestId;
        Result prior = results.get(key);
        if (prior != null) {
            if (!prior.operation().equals(operation) || !prior.arguments().equals(body)) return reject(sessionId,"REQUEST_ID_CONFLICT");
            return prior.value();
        }
        Map<String,Object> result;
        try {
            if (!playbackEpoch.equals(text(body,"expectedPlaybackEpoch"))
                    || !mode.name().equals(text(body,"expectedMode"))
                    || body == null || !body.path("expectedModeEpoch").canConvertToLong()
                    || modeEpoch != body.path("expectedModeEpoch").longValue()) {
                if (sessionId != null) sendState(sessionId, snapshot());
                result = reject(sessionId,"STALE_PLAYBACK_CONTEXT");
            } else {
                if (Set.of("next","pause","seek","refresh","rate","page","PAUSE","SKIP").contains(operation)) {
                    String current = currentState().nowPlaying() == null ? null : currentState().nowPlaying().playbackId();
                    if (!Objects.equals(current,text(body,"playbackId"))) throw new IllegalArgumentException("STALE_PLAYBACK_CONTEXT");
                }
                if ("mode".equals(operation) && !manager) throw new IllegalArgumentException("MANAGER_REQUIRED");
                if (action != null) action.run(); else dispatch(operation, body, sessionId == null ? "SYSTEM" : sessionId, manager);
                result = Map.of("ok",true,"requestId",requestId);
            }
        } catch (Exception error) {
            String code = errorCode(error);
            if ("STALE_PLAYBACK_CONTEXT".equals(code) && sessionId != null) sendState(sessionId,snapshot());
            result = reject(sessionId,code);
        }
        results.put(key,new Result(operation,body.deepCopy(),result));
        while (results.size() > 512) results.remove(results.keySet().iterator().next());
        return result;
    }

    private void dispatch(String operation, JsonNode body, String session, boolean manager) throws Exception {
        switch (operation) {
            case "mode" -> switchMode(MediaMode.valueOf(text(body,"mode")));
            case "enqueue" -> {
                String platform = text(body,"platform");
                if (mode == MediaMode.VIDEO && !"bilibili-video".equals(platform)
                        || mode == MediaMode.MUSIC && "bilibili-video".equals(platform)) throw new IllegalArgumentException("PLATFORM_NOT_AVAILABLE");
                EnqueueRequest request = new EnqueueRequest(platform,text(body,"musicId"));
                if (mode == MediaMode.MUSIC) music.enqueue(request,session); else video.enqueue(request,session);
            }
            case "playlist" -> {
                EnqueuePlaylistRequest request = new EnqueuePlaylistRequest(text(body,"platform"),text(body,"playlistId"));
                if (mode == MediaMode.MUSIC) music.enqueuePlaylist(request,session); else video.enqueuePlaylist(request,session);
            }
            case "next", "SKIP" -> { if (mode == MediaMode.MUSIC) music.skipToNext(session); else video.skipToNext(session); }
            case "pause", "PAUSE" -> { if (mode == MediaMode.MUSIC) music.togglePause(session); else video.togglePause(session); }
            case "shuffle", "SHUFFLE" -> { if (mode == MediaMode.MUSIC) music.cyclePlayMode(session); else video.cyclePlayMode(session); }
            case "TOGGLE_FAIR_SHUFFLE" -> { if (mode == MediaMode.MUSIC) music.toggleFairShuffle(session); else video.toggleFairShuffle(session); }
            case "TOGGLE_ALLOW_OFFLINE" -> { if (mode == MediaMode.MUSIC) music.toggleAllowOfflineShuffle(session); else video.toggleAllowOfflineShuffle(session); }
            case "seek" -> {
                SeekRequest request = new SeekRequest(text(body,"playbackId"),body.hasNonNull("position") ? body.get("position").longValue() : null);
                if (mode == MediaMode.MUSIC) music.seek(request,session,manager); else video.seek(request,session,manager);
            }
            case "top" -> { if (mode == MediaMode.MUSIC) music.topSong(text(body,"queueId"),session); else video.topSong(text(body,"queueId"),session); }
            case "remove" -> { if (mode == MediaMode.MUSIC) music.removeSongFromQueue(text(body,"queueId"),session,manager); else video.removeSongFromQueue(text(body,"queueId"),session,manager); }
            case "like" -> {
                requireMusic(); music.likeSong(session,body.hasNonNull("position") ? body.get("position").longValue() : null);
            }
            case "refresh" -> {
                if (mode != MediaMode.VIDEO) throw new IllegalArgumentException("FEATURE_NOT_AVAILABLE");
                video.refreshCurrentSource(session,text(body,"playbackId"),body.path("sourceRevision").asLong(),text(body,"reason"));
            }
            case "rate" -> {
                if (mode != MediaMode.VIDEO) throw new IllegalArgumentException("FEATURE_NOT_AVAILABLE");
                video.setPlaybackRate(body.path("playbackRate").asDouble(Double.NaN),session,manager);
            }
            case "page" -> {
                if (mode != MediaMode.VIDEO) throw new IllegalArgumentException("FEATURE_NOT_AVAILABLE");
                video.switchPage(body.path("page").asInt(0),session,manager);
            }
            case "recover" -> {
                if (mode != MediaMode.VIDEO) throw new IllegalArgumentException("FEATURE_NOT_AVAILABLE");
                video.recoverSource(session,text(body,"playbackId"),body.path("sourceRevision").asLong(),text(body,"queueId"));
            }
            case "lock" -> {
                if (!manager) throw new IllegalArgumentException("MANAGER_REQUIRED");
                String type = text(body,"type"); boolean locked = body.path("locked").asBoolean();
                if ("ALL".equals(type)) { if (mode == MediaMode.MUSIC) music.setAllLocks(locked); else video.setAllLocks(locked); }
                else if (mode == MediaMode.MUSIC) music.setLock(type,locked); else video.setLock(type,locked);
            }
            case "clear" -> {
                if (!manager) throw new IllegalArgumentException("MANAGER_REQUIRED");
                if ("OFFLINE".equals(text(body,"target"))) { if (mode == MediaMode.MUSIC) music.clearOfflineSongs(); else video.clearOfflineSongs(); }
                else { if (mode == MediaMode.MUSIC) music.clearQueue(); else video.clearQueue(); }
            }
            case "reset" -> {
                if (!manager) throw new IllegalArgumentException("MANAGER_REQUIRED");
                if (mode == MediaMode.MUSIC) music.resetSystem(); else video.resetSystem();
            }
            default -> throw new IllegalArgumentException("UNKNOWN_PLAYBACK_COMMAND");
        }
    }

    private void switchMode(MediaMode target) throws Exception {
        if (target == mode) return;
        // Strict SQLite transaction saves candidate settings and pending logical views without changing either engine.
        persistence.saveStrict(target.name());
        cancelImports(entry -> true);
        previews.clear();
        if (mode == MediaMode.MUSIC) music.freezeForModeSwitch(); else video.freezeForModeSwitch();
        music.setActive(target == MediaMode.MUSIC); video.setActive(target == MediaMode.VIDEO);
        stream.setPlaybackMode(target == MediaMode.MUSIC);
        mode = target; modeEpoch++;
        if (mode == MediaMode.MUSIC) music.activatePaused(); else video.activatePaused();
        sink.markQueueDirty(mode);
        sink.emit(mode,new PlayerEvent("INFO","MODE_SWITCH","SYSTEM",mode == MediaMode.VIDEO ? "已切换到一起看视频，请手动播放" : "已切换到一起听歌，请手动播放",null));
    }

    public void requireMusic() {
        mailbox.assertOnMailboxThread();
        if (mode != MediaMode.MUSIC) throw new IllegalArgumentException("当前视频模式不支持此功能");
    }
    public boolean isVideo() { mailbox.assertOnMailboxThread(); return mode == MediaMode.VIDEO; }
    public int clearUserQueue(String token) {
        mailbox.assertOnMailboxThread();
        return mode == MediaMode.MUSIC ? music.clearUserQueue(token) : video.clearUserQueue(token);
    }
    public void changed() { mailbox.assertOnMailboxThread(); sink.markStateDirty(mode); }
    public void membersChanged() {
        mailbox.assertOnMailboxThread(); video.membersChanged(); sink.markStateDirty(mode);
        messaging.convertAndSend(RoomContext.topic("/users/online"),users.getOnlineUserSummaries());
    }
    public void cookieChanged() { mailbox.assertOnMailboxThread(); cancelImports(entry -> true); video.cookieChanged(); sink.markStateDirty(mode); }

    public void tick(boolean heartbeat, boolean save, boolean cleanup, boolean empty) {
        start();
        int flags = (heartbeat ? 1 : 0) | (save ? 2 : 0) | (cleanup ? 4 : 0) | (empty ? 8 : 0);
        pendingTickFlags.getAndUpdate(previous -> previous | flags);
        mailbox.submitCoalesced("tick", () -> {
            if (closed || deleted) return;
            int pending = pendingTickFlags.getAndSet(0);
            if ((pending & 8) != 0) { music.pauseForEmptyRoom(); video.pauseForEmptyRoom(); stream.suspendForEmptyRoom(); }
            if (mode == MediaMode.MUSIC) music.playerLoop(); else video.playerLoop();
            if ((pending & 1) != 0) { sink.markStateDirty(mode); if (mode == MediaMode.MUSIC) stream.transcodeWatchdog(); }
            if ((pending & 2) != 0) persistence.saveNow();
            if ((pending & 4) != 0 && mode == MediaMode.MUSIC) music.cleanupIdlePlayer();
        });
    }

    private void requireVideoContext(JsonNode body) {
        requireOpen();
        if (mode != MediaMode.VIDEO || !playbackEpoch.equals(text(body,"expectedPlaybackEpoch"))
                || !"VIDEO".equals(text(body,"expectedMode")) || body == null || !body.path("expectedModeEpoch").canConvertToLong()
                || modeEpoch != body.path("expectedModeEpoch").asLong()) throw VideoFavorites.failure("STALE_PLAYBACK_CONTEXT");
    }
    private Map<String,Object> readContext(String requestId) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("roomId",RoomContext.require()); result.put("protocolVersion",3); result.put("playbackEpoch",playbackEpoch);
        result.put("mediaMode",mode.name()); result.put("modeEpoch",modeEpoch); result.put("requestId",requestId); return result;
    }
    public Map<String,Object> history(JsonNode context,int page,int limit,Long revision) {
        start(); return await(mailbox.submit(() -> {
            requireVideoContext(context);
            if(page<1 || limit<1 || limit>20) throw VideoFavorites.failure("INVALID_SELECTION");
            long current=video.getHistoryRevision();
            if(revision!=null && current!=revision) throw VideoFavorites.failure("HISTORY_CHANGED");
            List<Music> history=video.getHistorySnapshot();
            long offset=(long)(page-1)*limit;
            List<Map<String,Object>> items=history.subList((int)Math.min(offset,history.size()),(int)Math.min(offset+limit,history.size()))
                    .stream().map(item -> {
                        Map<String,Object> value=new LinkedHashMap<>(); value.put("canonicalId",item.id()); value.put("title",item.name());
                        value.put("cover",item.coverUrl());value.put("coverUrl",item.coverUrl()); value.put("ownerName",String.join(", ",item.artists())); value.put("durationMs",item.duration());
                        int separator=item.id().lastIndexOf(":p");
                        value.put("bvid",separator<0?item.id():item.id().substring(0,separator));
                        value.put("page",separator<0?1:Integer.parseInt(item.id().substring(separator+2)));
                        value.put("platform",item.platform()); return value;
                    }).toList();
            Map<String,Object> result=readContext(text(context,"requestId"));result.put("historyRevision",current);result.put("total",history.size());
            result.put("page",page);result.put("limit",limit);result.put("items",items);result.put("hasMore",offset+limit<history.size());return result;
        }));
    }
    private record PreviewWork(String id,VideoPreviewRegistry.Context owner,String folderId,int page,PlaybackMailbox.CompletionTicket ticket) {}
    public reactor.core.publisher.Mono<Map<String,Object>> favoritePreview(String browser,JsonNode context,String link,String previewId,int page) {
        start();
        return reactor.core.publisher.Mono.fromFuture(mailbox.submit(() -> {
            requireVideoContext(context);
            if(!video.isVideoSourceEnabled() || favorites==null) throw VideoFavorites.failure("PLATFORM_NOT_AVAILABLE");
            if(browser==null || page<1 || page>25) throw VideoFavorites.failure("INVALID_SELECTION");
            var owner=new VideoPreviewRegistry.Context(RoomContext.require(),playbackEpoch,modeEpoch,browser);
            String folder=null;
            String id=previewId;
            if(id==null) { folder=org.thornex.musicparty.service.api.BilibiliFavoriteLinkParser.parse(link); id=previews.create(owner,java.time.Instant.now()); }
            else {
                var cached=previews.cached(id,owner,page,java.time.Instant.now());
                if(cached!=null) return new Object[]{cached,null};
                var first=previews.cached(id,owner,1,java.time.Instant.now());
                if(first==null) throw VideoFavorites.failure("PREVIEW_NOT_FOUND");
                folder=first.folderId();
            }
            return new Object[]{null,new PreviewWork(id,owner,folder,page,mailbox.reserveCompletion())};
        })).flatMap(plan -> {
            if(plan[0]!=null) return reactor.core.publisher.Mono.fromFuture(mailbox.submit(() -> {requireVideoContext(context);return previewResponse((VideoFavorites.Preview)plan[0],context);}));
            PreviewWork work=(PreviewWork)plan[1];
            return favorites.page(work.folderId(),work.page()).flatMap(upstream -> reactor.core.publisher.Mono.fromFuture(work.ticket().commit(() -> {
                requireVideoContext(context);
                return previewResponse(previews.put(work.id(),work.owner(),upstream,java.time.Instant.now()),context);
            }))).doFinally(signal -> work.ticket().cancel());
        });
    }
    private Map<String,Object> previewResponse(VideoFavorites.Preview preview,JsonNode context) {
        Map<String,Object> result=readContext(text(context,"requestId"));
        result.putAll(WATCH_MAPPER.convertValue(preview,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));return result;
    }
    public CompletableFuture<Void> favoriteImport(String session,JsonNode body) {
        start(); return mailbox.submit(() -> {
            try { acceptImport(session,body); }
            catch(Throwable error) { importError(session,body,error); }
        });
    }
    private VideoImportRegistry.Owner importOwner(String session) {
        requireOpen();requireMemberRoom(session);
        User user=users.getUser(session).orElseThrow(() -> VideoFavorites.failure("NAMED_MEMBER_REQUIRED"));
        String browser=access.connection(session).watchIdentity();
        if(user.isGuest() || browser==null) throw VideoFavorites.failure("NAMED_MEMBER_REQUIRED");
        return new VideoImportRegistry.Owner(RoomContext.require(),playbackEpoch,browser,user.getToken());
    }
    private void acceptImport(String session,JsonNode body) throws Exception {
        var owner=importOwner(session);
        if(!playbackEpoch.equals(text(body,"expectedPlaybackEpoch"))) throw VideoFavorites.failure("STALE_PLAYBACK_CONTEXT");
        if(body==null || !body.path("items").isArray() || body.path("items").isEmpty()) throw VideoFavorites.failure("INVALID_SELECTION");
        if(body.path("items").size()>100) throw VideoFavorites.failure("IMPORT_BATCH_TOO_LARGE");
        for(JsonNode item:body.path("items")) if(!item.isObject() || !item.path("itemId").isTextual()
                || !item.path("page").isIntegralNumber() || !item.path("page").canConvertToInt()
                || item.path("page").asInt()<1 || item.path("page").asInt()>1000) throw VideoFavorites.failure("INVALID_SELECTION");
        if(text(body,"requestId")==null || text(body,"requestId").isBlank() || text(body,"requestId").length()>128) throw VideoFavorites.failure("INVALID_REQUEST_ID");
        List<VideoFavorites.Selection> selections=WATCH_MAPPER.convertValue(body.path("items"),new com.fasterxml.jackson.core.type.TypeReference<List<VideoFavorites.Selection>>(){});
        var parameters=new VideoImportRegistry.Parameters(text(body,"previewId"),selections);
        var key=new VideoImportRegistry.Key(owner,text(body,"requestId"));
        var prior=imports.existing(key,parameters,java.time.Instant.now());
        if(prior!=null) { sendImport(session,"IMPORT_STATUS",prior.operation());return; }
        requireVideoContext(body);
        if(!video.isVideoSourceEnabled()) throw VideoFavorites.failure("PLATFORM_NOT_AVAILABLE");
        if(selections.size()>Math.min(video.getImportBatchLimit(),100)) throw VideoFavorites.failure("IMPORT_BATCH_TOO_LARGE");
        var selected=previews.select(parameters.previewId(),new VideoPreviewRegistry.Context(owner.roomId(),owner.playbackEpoch(),modeEpoch,owner.browserIdentity()),selections,java.time.Instant.now());
        int slots=Math.min(selected.size(),video.availableImportSlots(owner.memberToken()));
        if(slots<1) throw VideoFavorites.failure("QUOTA_EXCEEDED");
        var entry=imports.reserve(key,parameters,session,modeEpoch,selected,java.time.Instant.now()).entry();
        PlaybackMailbox.CompletionTicket ticket=null;String reservation=null;
        try { ticket=mailbox.reserveCompletion(); reservation=video.reserveImportSlots(owner.memberToken(),slots); }
        catch(Throwable error) { if(ticket!=null) ticket.cancel(); if(reservation!=null) video.releaseImportSlots(reservation); imports.rollback(entry);throw error; }
        ImportWork work=new ImportWork(ticket,reservation);importWork.put(entry,work);
        sendImport(session,"IMPORT_ACCEPTED",entry.operation());imports.resolving(entry);
        final int granted=slots;
        try {
            work.subscription=favoriteResolver.resolve(selected.subList(0,slots)).subscribe(resolved -> work.ticket.commit(() -> commitImport(entry,work,resolved,granted)),
                    error -> work.ticket.commit(() -> commitImport(entry,work,List.of(),granted)));
        } catch(RuntimeException unavailable) { work.ticket.commit(() -> commitImport(entry,work,List.of(),granted)); }
    }
    private void commitImport(VideoImportRegistry.Entry entry,ImportWork work,List<org.thornex.musicparty.service.api.VideoFavoriteMetadataResolver.Resolved> resolved,int granted) {
        if(!entry.active() || importWork.get(entry)!=work) return;
        try {
            if(closed || deleted || mode!=MediaMode.VIDEO || entry.operation().modeEpoch()!=modeEpoch || !video.isVideoSourceEnabled()
                    || !importOwner(entry.sessionId()).equals(entry.key().owner())) { cancelImports(candidate -> candidate==entry);return; }
        } catch(RuntimeException departed) { cancelImports(candidate -> candidate==entry);return; }
        video.releaseImportSlots(work.reservation);importWork.remove(entry);
        List<VideoFavorites.ItemResult> results=new ArrayList<>();
        Map<String,org.thornex.musicparty.service.api.VideoFavoriteMetadataResolver.Resolved> byId=new HashMap<>();resolved.forEach(item -> byId.put(item.selection().itemId(),item));
        User user=users.getUser(entry.sessionId()).orElseThrow();
        for(int i=0;i<entry.selections().size();i++) {
            var item=entry.selections().get(i);
            if(i>=granted) { results.add(new VideoFavorites.ItemResult(item.itemId(),null,"QUOTA_EXCEEDED","QUOTA_EXCEEDED",false,null));continue; }
            var value=byId.get(item.itemId());
            if(value==null) results.add(new VideoFavorites.ItemResult(item.itemId(),null,"TEMPORARY_FAILURE","TEMPORARY_FAILURE",true,null));
            else if(value.failure()!=null) results.add(value.failure());
            else try {
                String queueId=video.commitImportedDescriptor(value.descriptor(),new UserSummary(user.getToken(),user.getSessionId(),user.getName(),false));
                results.add(new VideoFavorites.ItemResult(item.itemId(),value.descriptor().canonicalId(),"ADDED",null,false,queueId));
            } catch(RuntimeException error) {
                String code=error instanceof VideoFavorites.Failure failure?failure.getCode():error.getMessage();
                if(code==null || !Set.of("DUPLICATE","QUOTA_EXCEEDED","DURATION_EXCEEDED","PLATFORM_NOT_AVAILABLE").contains(code)) code="TEMPORARY_FAILURE";
                results.add(new VideoFavorites.ItemResult(item.itemId(),value.descriptor().canonicalId(),code,code,code.equals("TEMPORARY_FAILURE"),null));
            }
        }
        sendImport(entry.sessionId(),"IMPORT_RESULT",imports.finish(entry,results,java.time.Instant.now()));
    }
    public CompletableFuture<Void> importStatus(String session,JsonNode body) {
        start();return mailbox.submit(() -> {
            try {
                var owner=importOwner(session);
                if(!playbackEpoch.equals(text(body,"expectedPlaybackEpoch"))) throw VideoFavorites.failure("STALE_PLAYBACK_CONTEXT");
                sendImport(session,"IMPORT_STATUS",imports.status(owner,text(body,"operationId"),text(body,"originalRequestId"),java.time.Instant.now()));
            } catch(Throwable error) { importError(session,body,error); }
        });
    }
    private void sendImport(String session,String kind,VideoFavorites.Operation operation) {
        Map<String,Object> payload=WATCH_MAPPER.convertValue(operation,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        payload.put("kind",kind);messaging.convertAndSendToUser(session,"/queue/video/import",payload,headers(session));
    }
    private void importError(String session,JsonNode body,Throwable error) {
        String code=error instanceof VideoFavorites.Failure failure?failure.getCode():"TEMPORARY_FAILURE";
        var safe=VideoFavorites.failure(code); Map<String,Object> payload=readContext(text(body,"requestId")!=null?text(body,"requestId"):text(body,"originalRequestId"));
        payload.put("kind","IMPORT_ERROR");payload.put("operationId",text(body,"operationId"));payload.put("ok",false);payload.put("code",code);
        payload.put("message",safe.getMessage());payload.put("retryable",safe.isRetryable());
        messaging.convertAndSendToUser(session,"/queue/video/import",payload,headers(session));
    }
    private void cancelImports(java.util.function.Predicate<VideoImportRegistry.Entry> predicate) {
        for(var entry:imports.cancel(predicate,java.time.Instant.now())) {
            ImportWork work=importWork.remove(entry);
            if(work!=null) { work.ticket.cancel();video.releaseImportSlots(work.reservation);if(work.subscription!=null) work.subscription.dispose(); }
            if(!closed && !deleted) sendImport(entry.sessionId(),"IMPORT_RESULT",entry.operation());
        }
    }
    private void cancelImportsForUser(String token) { cancelImports(entry -> "ALL".equals(token) || token==null || token.equals(entry.key().owner().memberToken())); }
    public void sessionDisconnected(String session) { mailbox.assertOnMailboxThread();cancelImports(entry -> entry.sessionId().equals(session));interactionStates.remove(session); }
    public CompletableFuture<Void> interactionResync(String session,JsonNode body) {
        start();return mailbox.submit(() -> { requireMemberRoom(session); requireVideoContext(body);sendInteraction(session,true); });
    }
    private void publishInteractions() { for(var member:users.getOnlineUserSummaries()) sendInteraction(member.sessionId(),false); }
    private void sendInteraction(String session,boolean force) {
        Map<String,Object> value=new LinkedHashMap<>(video.interaction(session));
        value.put("mediaMode",mode.name());value.put("modeEpoch",modeEpoch);value.put("playbackEpoch",playbackEpoch);
        if(mode!=MediaMode.VIDEO) { value.put("canVote",false);value.put("hasVoted",false);value.put("playbackId",null);value.put("reason","INACTIVE"); }
        if(!force && value.equals(interactionStates.get(session))) return;
        interactionStates.put(session,new LinkedHashMap<>(value));value.put("protocolVersion",3);value.put("roomId",RoomContext.require());
        value.put("stateRevision",stateRevision);value.put("interactionRevision",++interactionRevision);
        messaging.convertAndSendToUser(session,"/queue/video/interaction",value,headers(session));
    }
    private PlayerState currentState() { return mode == MediaMode.MUSIC ? music.getCurrentPlayerState() : video.getCurrentPlayerState(); }
    private PlayerState snapshot() {
        mailbox.assertOnMailboxThread();
        PlayerState raw = currentState();
        long time = raw.nowPlaying() == null ? System.currentTimeMillis() : raw.nowPlaying().serverTime();
        return raw.versioned(playbackEpoch,mode.name(),modeEpoch,++stateRevision,queueRevision,time,
                mode == MediaMode.VIDEO ? video.getSourceStatus() : "EMPTY",
                mode == MediaMode.VIDEO ? video.getSourceRetryAt() : null);
    }

    public PlayerState read() { start(); return await(mailbox.submit(this::snapshot)); }
    public void resync(String session) {
        start(); mailbox.submit(() -> {
            var connection = access.connection(session);
            if (!RoomContext.require().equals(connection.roomId())) throw new IllegalArgumentException("ACCESS_DENIED");
            sendState(session,snapshot());
        });
    }
    public void clock(String session, JsonNode request) {
        long received = System.currentTimeMillis();
        start(); mailbox.submit(() -> {
            if (!RoomContext.require().equals(access.connection(session).roomId())) throw new IllegalArgumentException("ACCESS_DENIED");
            messaging.convertAndSendToUser(session,"/queue/player/clock",Map.of("requestId",Objects.toString(text(request,"requestId"),""),
                    "serverReceivedAt",received,"serverSentAt",System.currentTimeMillis()),headers(session));
        });
    }

    private void flush() {
        if (!ready || closed || deleted) return;
        PlaybackEventSink.Drain dirty = sink.take(mode);
        if (dirty.queueDirty()) queueRevision++;
        if (dirty.stateDirty() || dirty.queueDirty()) {
            PlayerState state = snapshot();
            publisher.publishEvent(new PlayerStateEvent(this,state));
            if (dirty.queueDirty()) messaging.convertAndSend(RoomContext.topic("/player/queue"),
                    new PlaybackQueueSnapshot(3,playbackEpoch,mode.name(),modeEpoch,queueRevision,state.queue()));
            publishInteractions();
        }
        for (Object event : dirty.events()) publisher.publishEvent(new CommittedPlaybackNotice(event,playbackEpoch,mode.name(),modeEpoch));
    }
    private void sendState(String session, PlayerState state) { messaging.convertAndSendToUser(session,"/queue/player/state",state,headers(session)); }
    private org.springframework.messaging.MessageHeaders headers(String session) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(session); headers.setLeaveMutable(true); return headers.getMessageHeaders();
    }
    private Map<String,Object> reject(String session, String code) {
        if (session != null) privateError(session,code,code);
        return Map.of("ok",false,"code",code,"message",code);
    }
    private void privateError(String session,String code,String message) {
        PlayerEvent error = new PlayerEvent("ERROR",code,"SYSTEM",message,null);
        if (mailbox.isOnMailboxThread()) error = error.versioned(playbackEpoch,mode.name(),modeEpoch);
        messaging.convertAndSendToUser(session,"/queue/events",error,headers(session));
    }
    private void requireOpen() { mailbox.assertOnMailboxThread(); if (!ready) throw new IllegalStateException("PLAYBACK_INITIALIZATION_FAILED"); if (closed || deleted) throw new IllegalStateException("ROOM_CLOSED"); }
    private static String text(JsonNode body,String field) { return body == null || !body.hasNonNull(field) ? null : body.get(field).asText(); }
    private static String errorCode(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        if(error instanceof VideoFavorites.Failure failure) return failure.getCode();
        return error.getMessage() == null ? "PLAYBACK_FAILED" : error.getMessage();
    }
    private <T> T await(CompletableFuture<T> future) {
        try { return future.get(10,TimeUnit.SECONDS); }
        catch (TimeoutException e) { future.cancel(false); throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"ROOM_BUSY"); }
        catch (Exception e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,errorCode(e)); }
    }

    public void close(boolean deleting) {
        start();
        CompletableFuture<Void> finalization = mailbox.quiesce(() -> {
            deleted = deleting; closed = true;
            cancelImports(entry -> true);
            previews.clear();
            try {
                music.invalidateAsyncForClose(); video.invalidateAsyncForClose();
                if (!deleting) persistence.saveStrict(mode.name());
            } catch (Exception error) { log.error("Final playback save failed for room {}",RoomContext.require(),error); }
            finally { try { stream.suspendForEmptyRoom(); } finally { sink.clear(); } }
        });
        // HTTP request timeouts can cancel unstarted commands. This lifecycle barrier must run before scope.destroy.
        // Quiesce has already invalidated network tickets; only accepted immediate work and the final SQLite save remain.
        boolean interrupted = false;
        try {
            for (;;) {
                try { finalization.get(10,TimeUnit.SECONDS); return; }
                catch (TimeoutException error) { log.warn("Room {} is still draining accepted playback work before shutdown",mailbox.roomId()); }
                catch (InterruptedException error) { interrupted = true; }
                catch (ExecutionException error) { throw new IllegalStateException("Room playback finalization failed",error.getCause()); }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    @PreDestroy public void cleanup() { closed = true; }
}
