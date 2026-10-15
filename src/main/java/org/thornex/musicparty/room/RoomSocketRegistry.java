package org.thornex.musicparty.room;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomSocketRegistry {
    private final Map<String,WebSocketSession> sockets=new ConcurrentHashMap<>();
    public void add(WebSocketSession s) { sockets.put(s.getId(),s); }
    public void remove(String id) { sockets.remove(id); }
    public void close(String id) { WebSocketSession s=sockets.get(id); if(s!=null) try { s.close(CloseStatus.POLICY_VIOLATION); } catch(Exception ignored) {} }
}
