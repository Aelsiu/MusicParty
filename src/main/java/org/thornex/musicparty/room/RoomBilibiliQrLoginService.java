package org.thornex.musicparty.room;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.playback.RoomPlaybackCoordinator;
import org.thornex.musicparty.service.QueuePersistenceService;
import org.thornex.musicparty.service.api.BilibiliMusicApiService;
import org.thornex.musicparty.service.api.BilibiliQrLoginClient;

import java.time.Clock;
import java.util.Map;

@Service @RoomScoped
public class RoomBilibiliQrLoginService {
    private final RoomAccessService access;
    private final BilibiliQrLoginClient client;
    private final QueuePersistenceService persistence;
    private final BilibiliMusicApiService music;
    private final AppProperties config;
    private final RoomPlaybackCoordinator playback;
    private final Clock clock;
    private String task, key, creator, room, state = "CANCELLED";
    private long generation, expiresAt, nextPollAt;
    private boolean polling;

    @org.springframework.beans.factory.annotation.Autowired
    public RoomBilibiliQrLoginService(RoomAccessService access, BilibiliQrLoginClient client, QueuePersistenceService persistence,
                                    BilibiliMusicApiService music, AppProperties config, RoomPlaybackCoordinator playback) {
        this(access, client, persistence, music, config, playback, Clock.systemUTC());
    }
    RoomBilibiliQrLoginService(RoomAccessService access, BilibiliQrLoginClient client, QueuePersistenceService persistence,
                              BilibiliMusicApiService music, AppProperties config, RoomPlaybackCoordinator playback, Clock clock) {
        this.access=access; this.client=client; this.persistence=persistence; this.music=music; this.config=config; this.playback=playback; this.clock=clock;
    }
    public Map<String,Object> create(String manager) {
        String roomId = RoomContext.require();
        access.own(manager, roomId);
        long version;
        synchronized (this) { clear(); version=generation; creator=manager; room=roomId; state="CREATING"; }
        BilibiliQrLoginClient.QrCode qr;
        try { qr=client.create(); }
        catch (RuntimeException e) { synchronized(this) { if(version==generation) clear(); } throw e; }
        synchronized(this) {
            if(version != generation) throw cancelled();
            try { access.own(manager, roomId); }
            catch (RuntimeException e) { clear(); throw e; }
            key=qr.key(); task=access.token(); expiresAt=clock.millis()+180000; state="WAITING";
            return Map.of("task",task,"url",qr.url(),"expiresAt",expiresAt,"state",state);
        }
    }
    public Map<String,String> check(String taskId, String manager) {
        String roomId=RoomContext.require(); access.own(manager,roomId);
        long version; String loginKey;
        synchronized(this) {
            requireTask(taskId, manager, roomId);
            if (terminal()) return status();
            if (clock.millis()>=expiresAt) {state="EXPIRED";key=null;return status();}
            if (polling || clock.millis()<nextPollAt) return status();
            polling=true; nextPollAt=clock.millis()+1500; version=generation; loginKey=key;
        }
        try {
            BilibiliQrLoginClient.Poll result=client.poll(loginKey);
            if (result.cookie()!=null) {
                return playback.manage(manager, () -> {
                    synchronized(this) {
                        requireCurrent(version,taskId,manager,roomId);
                        if (terminal()) return status();
                        if(clock.millis()>=expiresAt) {state="EXPIRED";key=null;return status();}
                        try {
                            persistence.saveBilibiliCookieStrict(result.cookie(), () -> {
                                requireCurrent(version,taskId,manager,roomId);
                                access.own(manager,roomId);
                                if(clock.millis()>=expiresAt) throw cancelled();
                            });
                        } catch (ResponseStatusException e) { throw e; }
                        catch (Exception e) {state="FAILED";key=null;return status();}
                        // Publishing follows the durable write; these existing operations contain no network wait.
                        config.getBilibili().setCookie(result.cookie()); music.updateCookie(result.cookie()); playback.cookieChanged();
                        state="SUCCESS";key=null;return status();
                    }
                });
            }
            synchronized(this) {
                requireCurrent(version,taskId,manager,roomId); access.own(manager,roomId);
                if (!terminal()) state=clock.millis()>=expiresAt?"EXPIRED":result.state();
                if(terminal()) key=null;
                return status();
            }
        } finally { synchronized(this) { if(version==generation) polling=false; } }
    }
    public synchronized void cancel(String taskId, String manager) {
        String roomId=RoomContext.require(); access.own(manager,roomId);
        if(task!=null && task.equals(taskId) && creator.equals(manager) && room.equals(roomId)) clear();
    }
    private void requireTask(String taskId,String manager,String roomId) {
        if(task==null || !task.equals(taskId) || !creator.equals(manager) || !room.equals(roomId)) throw cancelled();
    }
    private void requireCurrent(long version,String taskId,String manager,String roomId) {
        if(version!=generation) throw cancelled(); requireTask(taskId,manager,roomId);
    }
    private boolean terminal() {return state.equals("SUCCESS") || state.equals("FAILED") || state.equals("EXPIRED");}
    private Map<String,String> status() {return Map.of("state",state);}
    @PreDestroy public synchronized void destroy() {clear();}
    private void clear() {generation++;task=null;key=null;creator=null;room=null;state="CANCELLED";polling=false;nextPollAt=0;}
    private static ResponseStatusException cancelled() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"登录任务已取消，请重新扫码");}
}
