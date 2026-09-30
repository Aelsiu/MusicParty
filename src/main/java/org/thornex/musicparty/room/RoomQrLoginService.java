package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.MusicPlayerService;
import org.thornex.musicparty.service.QueuePersistenceService;
import java.time.Duration;
import java.util.Map;

@Service @RoomScoped
public class RoomQrLoginService {
    private final WebClient web;
    private final AppProperties config;
    private final RoomAccessService access;
    private final NeteaseMusicApiService netease;
    private final MusicPlayerService player;
    private final QueuePersistenceService persistence;
    private String key,task,creator;
    private long expiresAt;
    private String state;
    private long generation;
    public RoomQrLoginService(WebClient web,AppProperties config,RoomAccessService access,NeteaseMusicApiService netease,MusicPlayerService player,QueuePersistenceService persistence) {this.web=web;this.config=config;this.access=access;this.netease=netease;this.player=player;this.persistence=persistence;}
    private JsonNode request(String path,Object... variables) {
        try { return web.get().uri(config.getNetease().getBaseUrl()+path,variables).retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10)); }
        catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"网易云登录服务暂不可用，请重试"); }
    }
    public Map<String,Object> create(String manager) {
        access.own(manager,RoomContext.require());
        long version;
        synchronized(this) {version=++generation;creator=manager;task=null;state="WAITING";}
        JsonNode result=request("/login/qr/key?timestamp={t}",System.currentTimeMillis());
        String newKey=result.path("data").path("unikey").asText();
        if(newKey.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"二维码创建失败");
        JsonNode image=request("/login/qr/create?key={key}&qrimg=true&timestamp={t}",newKey,System.currentTimeMillis());
        String data=image.path("data").path("qrimg").asText();
        if(!data.startsWith("data:image/png;base64,")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"二维码图片无效");
        synchronized(this) {
            if(version!=generation) throw cancelled();
            access.own(manager,RoomContext.require());
            key=newKey;task=access.token();expiresAt=System.currentTimeMillis()+180000;
            return Map.of("task",task,"image",data,"expiresAt",expiresAt,"state",state);
        }
    }
    public Map<String,String> check(String taskId,String manager) {
        access.own(manager,RoomContext.require());
        long version;String loginKey;
        synchronized(this) {
            if(task==null || !task.equals(taskId) || !creator.equals(manager)) throw cancelled();
            if("SUCCESS".equals(state)) return Map.of("state",state);
            if(System.currentTimeMillis()>=expiresAt) {state="EXPIRED";return Map.of("state",state);}
            version=generation;loginKey=key;
        }
        JsonNode result=request("/login/qr/check?key={key}&timestamp={t}",loginKey,System.currentTimeMillis());
        int code=result.path("code").asInt();
        String next=switch(code) {case 800 -> "EXPIRED";case 801 -> "WAITING";case 802 -> "CONFIRMING";case 803 -> "VALIDATING";default -> "FAILED";};
        String cookie=result.path("cookie").asText();
        boolean valid=false;
        if(code==803) {
            try {valid=!cookie.isBlank() && Boolean.TRUE.equals(netease.checkCookie(cookie).block(Duration.ofSeconds(10)));}
            catch(RuntimeException e) {next="FAILED";}
        }
        synchronized(this) {
            if(version!=generation || task==null) throw cancelled();
            // Cancellation and permission changes win over in-flight network responses.
            access.own(manager,RoomContext.require());
            if("SUCCESS".equals(state)) return Map.of("state",state);
            state=next;
            if(code==803) {
                if(!valid) state="FAILED";
                else {netease.updateCookie(cookie);persistence.saveNow();player.broadcastFullPlayerState();state="SUCCESS";}
            }
            return Map.of("state",state);
        }
    }
    public synchronized void cancel(String taskId,String manager) {
        if(task!=null && task.equals(taskId) && creator.equals(manager)) clear();
    }
    @jakarta.annotation.PreDestroy public synchronized void destroy() { clear(); }
    private void clear() {generation++;task=null;key=null;creator=null;state="CANCELLED";}
    private ResponseStatusException cancelled() {return new ResponseStatusException(HttpStatus.NOT_FOUND,"登录任务已取消");}
}
