package org.thornex.musicparty.room;

import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.stream.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Service @Slf4j
public class RoomLifecycleService {
    private final RoomRepository repository;
    private final RoomAccessService access;
    private final RoomScope scope;
    private final RoomSocketRegistry sockets;
    private final ApplicationContext context;
    private final SimpMessagingTemplate messaging;
    private final MultiRoomProperties properties;
    private final Map<String,Long> emptySince=new ConcurrentHashMap<>();
    private long tick;
    public RoomLifecycleService(RoomRepository repository,RoomAccessService access,RoomScope scope,RoomSocketRegistry sockets,ApplicationContext context,SimpMessagingTemplate messaging,MultiRoomProperties properties) { this.repository=repository;this.access=access;this.scope=scope;this.sockets=sockets;this.context=context;this.messaging=messaging;this.properties=properties; }
    public void departed(String id) { if(access.count(id)==0) emptySince.putIfAbsent(id,System.currentTimeMillis()); }
    public void joined(String id) { emptySince.remove(id); try(var ignored=RoomContext.enter(id)) { context.getBean(QueuePersistenceService.class).ensureLoaded(); } }
    public void deleted(String id) {
        messaging.convertAndSend("/topic/rooms/"+id+"/lifecycle",Map.of("action","ROOM_DELETED"));
        for(String session:access.sessions(id)) sockets.close(session);
        access.revokeRoom(id);emptySince.remove(id);scope.destroy(id);
        RoomCacheCleanup.delete(id);
    }
    public void revokeInvalidManagers() { for(String id:scope.activeIds()) for(String s:access.sessions(id)) try { access.connection(s); } catch(RuntimeException e) { sockets.close(s); } }
    @jakarta.annotation.PreDestroy public void shutdown() { for(String id:scope.activeIds()) {for(String session:access.sessions(id)) sockets.close(session);scope.destroy(id);} }
    @Scheduled(fixedDelay=1000) public void tick() {
        long now=System.currentTimeMillis(); tick++;
        repository.rotate(now); if(tick%60==0) access.cleanup();
        for(String id:scope.activeIds()) {
            if(!repository.exists(id)) {deleted(id);continue;}
            try(var ignored=RoomContext.enter(id)) {
                MusicPlayerService player=context.getBean(MusicPlayerService.class);
                Long empty=emptySince.get(id);
                if(empty!=null && access.count(id)==0 && now-empty>=properties.getReconnectGraceSeconds()*1000) {
                    player.pauseForEmptyRoom(); context.getBean(LiveStreamService.class).suspendForEmptyRoom(); emptySince.remove(id);
                }
                player.playerLoop();
                if(tick%5==0) { player.broadcastSyncHeartbeat();context.getBean(LiveStreamService.class).transcodeWatchdog();messaging.convertAndSend(RoomContext.topic("/pairing"),Map.of("nextUpdateAt",(RoomRepository.epoch(now)+1)*600000)); }
                if(tick%5==0) {
                    var users=context.getBean(UserService.class);
                    messaging.convertAndSend(RoomContext.topic("/users/online"),users.getOnlineUserSummaries());
                    for(String session:access.sessions(id)) users.getUser(session).ifPresent(user -> {
                        var headers=org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(org.springframework.messaging.simp.SimpMessageType.MESSAGE);
                        headers.setSessionId(session);headers.setLeaveMutable(true);
                        messaging.convertAndSendToUser(session,"/queue/profile",Map.of("name",user.getName(),"bindings",Map.copyOf(user.getBindings())),headers.getMessageHeaders());
                    });
                }
                if(tick%30==0) context.getBean(QueuePersistenceService.class).saveNow();
                if(tick%600==0) player.cleanupIdlePlayer();
                if(tick%3600==0) { context.getBean(UserService.class).cleanupExpiredUsers();context.getBean(StreamTokenService.class).cleanup(); }
            } catch(Exception e) { log.error("Room background task failed for {}",id,e); }
        }
        if(tick%5==0) revokeInvalidManagers();
    }
}
