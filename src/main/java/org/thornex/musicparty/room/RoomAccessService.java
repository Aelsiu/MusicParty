package org.thornex.musicparty.room;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomAccessService {
    public record Manager(String token,String licenseId,String version,long expiresAt) { public boolean root() { return "ROOT".equals(licenseId); } }
    public record Admission(String token,String roomId,long epoch,long expiresAt) {}
    public record Connection(String roomId,String managerToken,String userToken) {}
    private final RoomRepository repository;
    private final MultiRoomProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String,Manager> managers = new ConcurrentHashMap<>();
    private final Map<String,Admission> admissions = new ConcurrentHashMap<>();
    private final Map<String,Connection> connections = new ConcurrentHashMap<>();
    private final Map<String,Attempt> attempts = new ConcurrentHashMap<>();
    private record Attempt(int count,long resetAt) {}
    public RoomAccessService(RoomRepository repository,MultiRoomProperties properties) { this.repository=repository;this.properties=properties; }
    public Manager login(String key,String address) {
        limit(address);
        String id=null;
        if (equal(key,repository.rootKey())) id="ROOT";
        else for (var license:repository.licenses()) if (equal(key,license.key())) { id=license.id(); break; }
        if (id == null) throw denied("许可密钥无效");
        attempts.remove(address); cleanup();
        Manager session=new Manager(token(),id,digest(key),System.currentTimeMillis()+Math.max(1,properties.getManagementSessionHours())*3600000);
        managers.put(session.token(),session); return session;
    }
    public Manager manager(String token) {
        Manager manager=token == null ? null : managers.get(token);
        if (manager == null || manager.expiresAt() <= System.currentTimeMillis()) throw denied("管理会话已失效，请重新验证");
        String key=manager.root()?repository.rootKey():repository.license(manager.licenseId()).map(RoomRepository.License::key).orElse(null);
        if (key == null || !equal(manager.version(),digest(key))) { managers.remove(token); throw denied("许可已更新或删除，请重新验证"); }
        return manager;
    }
    public Manager own(String token,String roomId) { Manager m=manager(token); if (!m.root() && !repository.room(roomId).ownerId().equals(m.licenseId())) throw denied("无权管理该房间"); return m; }
    public Manager root(String token) { Manager m=manager(token); if (!m.root()) throw denied("需要最高许可"); return m; }
    public Admission join(String code,String address) {
        limit(address); long now=System.currentTimeMillis(); repository.rotate(now);
        var room=repository.rooms().stream().filter(r -> Objects.equals(r.pairingCode(),code)).findFirst().orElseThrow(() -> denied("配对码无效或已更新"));
        attempts.remove(address); cleanup();
        Admission value=new Admission(token(),room.id(),repository.pairingEpoch(now),repository.nextPairingUpdateAt(now));
        repository.saveAdmission(value); admissions.put(value.token(),value); return value;
    }
    public void admission(String token,String roomId) {
        Admission a=token==null?null:admissions.computeIfAbsent(token,repository::admission);
        if (a==null || !a.roomId().equals(roomId) || a.epoch()!=repository.pairingEpoch(System.currentTimeMillis()) || !repository.exists(roomId)) throw denied("配对码已更新，请重新进入房间");
    }
    public void member(String token,String managerToken,String roomId) {
        if (managerToken!=null && !managerToken.isBlank()) { own(managerToken,roomId); return; }
        // An uninterrupted connection retains its admission after a pairing rotation.
        if (token!=null && connections.values().stream().anyMatch(c -> c.roomId().equals(roomId) && c.userToken().equals(token))) return;
        admission(token,roomId);
    }
    public void connect(String session,String roomId,String token,String managerToken) {
        repository.room(roomId);
        if (managerToken!=null && !managerToken.isBlank()) own(managerToken,roomId); else admission(token,roomId);
        connections.put(session,new Connection(roomId,managerToken,token==null?"":token));
    }
    public Connection connection(String session) {
        Connection c=connections.get(session);
        if (c==null || !repository.exists(c.roomId())) throw denied("房间连接已失效");
        if (c.managerToken()!=null && !c.managerToken().isBlank()) own(c.managerToken(),c.roomId());
        return c;
    }
    public Connection disconnect(String session) { return connections.remove(session); }
    public int count(String roomId) { return (int)connections.values().stream().filter(c -> c.roomId().equals(roomId)).count(); }
    public Set<String> sessions(String roomId) { Set<String> ids=new HashSet<>(); connections.forEach((id,c)->{if(c.roomId().equals(roomId))ids.add(id);});return ids; }
    public void revokeLicense(String id) { managers.values().removeIf(m->m.licenseId().equals(id)); }
    public void revokeRoom(String id) { admissions.values().removeIf(a->a.roomId().equals(id)); }
    public String token() { byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private boolean equal(String a,String b) { return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    private String digest(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private synchronized void limit(String address) {
        long now=System.currentTimeMillis(); Attempt old=attempts.get(address);
        if(old==null || old.resetAt()<now) old=new Attempt(0,now+60000);
        if(old.count()>=10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"尝试过于频繁，请稍后重试");
        if(attempts.size()>10000) attempts.entrySet().removeIf(e->e.getValue().resetAt()<now);
        attempts.put(address,new Attempt(old.count()+1,old.resetAt()));
    }
    public void cleanup() { long now=System.currentTimeMillis();managers.values().removeIf(m->m.expiresAt()<now);admissions.values().removeIf(a->a.expiresAt()+1800000<now);attempts.values().removeIf(a->a.resetAt()<now);repository.cleanupAdmissions(now); }
    private ResponseStatusException denied(String message) { return new ResponseStatusException(HttpStatus.FORBIDDEN,message); }
}
