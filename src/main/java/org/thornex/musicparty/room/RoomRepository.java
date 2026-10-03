package org.thornex.musicparty.room;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.sql.*;
import java.security.SecureRandom;
import java.util.*;

/** SQLite owns room data and pairing history, licenses.json owns ordinary keys. */
@Service
public class RoomRepository {
    public record License(String id, String key, String note) {
        public License { note = note == null ? "" : note; }
        public License(String id, String key) { this(id, key, ""); }
    }
    public record Room(String id, String name, String ownerId, long createdAt, String pairingCode, long pairingEpoch, boolean autoOpen, boolean pairingOpen, boolean publicRoom, long accessVersion, boolean shareEnabled) {}
    private static final int CODE_SPACE = 36 * 36 * 36 * 36;
    private final MultiRoomProperties properties;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private Connection db;
    private int pairingIntervalMinutes = 10;
    private long pairingStartMs, pairingBaseEpoch;
    private List<License> licenses = new ArrayList<>();
    public RoomRepository(MultiRoomProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }
    @PostConstruct public synchronized void initialize() throws Exception {
        if (!RoomValidation.key(properties.getRootKey())) throw new IllegalStateException("Configure app.rooms.root-key in the server config, 8–16 printable ASCII characters without whitespace");
        Path path = Path.of(properties.getDatabase()).toAbsolutePath();
        Files.createDirectories(path.getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS rooms(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner_id TEXT NOT NULL,created_at INTEGER NOT NULL,pair_code TEXT,pair_epoch INTEGER NOT NULL DEFAULT 0,payload TEXT,config TEXT,auto_open INTEGER NOT NULL DEFAULT 1)");
            s.execute("CREATE TABLE IF NOT EXISTS pairing_cooldown(code TEXT PRIMARY KEY,until_ms INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS profiles(token TEXT PRIMARY KEY,payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS creations(request_key TEXT PRIMARY KEY,room_id TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS admissions(token TEXT PRIMARY KEY,room_id TEXT NOT NULL,epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS system_config(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS pairing_clock(id INTEGER PRIMARY KEY CHECK(id=1),interval_minutes INTEGER NOT NULL,start_ms INTEGER NOT NULL,base_epoch INTEGER NOT NULL)");
        }
        addColumnIfMissing("rooms","pairing_open","INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("rooms","share_enabled","INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("rooms","public_room","INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("rooms","access_version","INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("admissions","public_entry","INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("admissions","access_version","INTEGER NOT NULL DEFAULT 0");
        try (Statement s = db.createStatement(); ResultSet clock = s.executeQuery("SELECT * FROM pairing_clock WHERE id=1")) {
            if (clock.next()) { pairingIntervalMinutes=clock.getInt("interval_minutes");pairingStartMs=clock.getLong("start_ms");pairingBaseEpoch=clock.getLong("base_epoch"); }
        }
        Path list = Path.of(properties.getLicenseFile());
        if (Files.exists(list)) licenses = mapper.readValue(Files.readString(list), new TypeReference<List<License>>() {});
        else writeLicenses(licenses);
        validateLicenses(licenses);
        // Recover a file update interrupted before its corresponding room deletion.
        for (Room room : rooms()) if (!"ROOT".equals(room.ownerId()) && licenses.stream().noneMatch(l -> l.id().equals(room.ownerId()))) {
            deleteRoom(room.id());RoomCacheCleanup.delete(room.id());
        }
        rotate(System.currentTimeMillis());
    }
    @PreDestroy public synchronized void close() throws SQLException { if (db != null) db.close(); }
    public synchronized List<License> licenses() { return List.copyOf(licenses); }
    public synchronized Optional<License> license(String id) { return licenses.stream().filter(l -> l.id().equals(id)).findFirst(); }
    public String rootKey() { return properties.getRootKey(); }
    public synchronized List<Room> rooms() {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM rooms ORDER BY created_at,id")) {
            List<Room> result = new ArrayList<>(); while (r.next()) result.add(readRoom(r)); return result;
        } catch (SQLException e) { throw storage(e); }
    }
    public synchronized Room room(String id) { return rooms().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "房间已删除")); }
    public synchronized boolean exists(String id) { return rooms().stream().anyMatch(r -> r.id().equals(id)); }
    private void addColumnIfMissing(String table,String name,String definition) throws SQLException {
        try (Statement s=db.createStatement(); ResultSet columns=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(columns.next()) if(name.equals(columns.getString("name"))) return;
        }
        try(Statement s=db.createStatement()) { s.execute("ALTER TABLE "+table+" ADD COLUMN "+name+" "+definition); }
    }
    private Room readRoom(ResultSet r) throws SQLException { return new Room(r.getString("id"),r.getString("name"),r.getString("owner_id"),r.getLong("created_at"),r.getString("pair_code"),r.getLong("pair_epoch"),r.getInt("auto_open") != 0,r.getInt("pairing_open") != 0,r.getInt("public_room") != 0,r.getLong("access_version"),r.getInt("share_enabled") != 0); }
    public synchronized Room create(String owner, String name, String requestId) {
        if (!RoomValidation.name(name)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "房间名须为 2–16 个可见字符，不含换行或不可见控制字符");
        if (requestId == null || !requestId.matches("[a-zA-Z0-9-]{16,64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少有效的创建请求标识");
        String requestKey = owner + ":" + requestId;
        try (PreparedStatement s = db.prepareStatement("SELECT room_id FROM creations WHERE request_key=?")) {
            s.setString(1,requestKey);
            try (ResultSet r = s.executeQuery()) { if (r.next() && exists(r.getString(1))) return room(r.getString(1)); }
        } catch (SQLException e) { throw storage(e); }
        if (rooms().stream().filter(r -> r.ownerId().equals(owner)).count() >= 9) throw new ResponseStatusException(HttpStatus.CONFLICT, "每个许可最多创建 9 个房间");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", id;
        do { StringBuilder b = new StringBuilder(); for (int i=0;i<8;i++) b.append(alphabet.charAt(random.nextInt(alphabet.length()))); id = b.toString(); } while (exists(id));
        long now = System.currentTimeMillis(); rotate(now);
        String createdId=id;
        transaction(() -> {
            update("INSERT INTO rooms(id,name,owner_id,created_at,pair_code,pair_epoch) VALUES(?,?,?,?,?,?)",createdId,name,owner,now,newCode(now,new HashSet<>()),pairingEpoch(now));
            update("INSERT INTO creations(request_key,room_id) VALUES(?,?)",requestKey,createdId);
        });
        return room(id);
    }
    public synchronized void deleteRoom(String id) {
        Room room = room(id);
        transaction(() -> {
            update("INSERT INTO pairing_cooldown(code,until_ms) VALUES(?,?) ON CONFLICT(code) DO UPDATE SET until_ms=MAX(until_ms,excluded.until_ms)",room.pairingCode(),System.currentTimeMillis()+1800000);
            update("DELETE FROM admissions WHERE room_id=?",id);
            update("DELETE FROM creations WHERE room_id=?",id);
            update("DELETE FROM rooms WHERE id=?",id);
        });
    }
    public synchronized void rotate(long now) {
        long period = pairingEpoch(now);
        if(rooms().stream().noneMatch(room -> room.pairingEpoch()!=period)) return;
        transaction(() -> rotateCodes(now, period, false));
    }
    private void rotateCodes(long now, long period, boolean force) {
        List<Room> stale = rooms().stream().filter(r -> force || r.pairingEpoch() != period).toList();
        if (stale.isEmpty()) return;
            Set<String> reserved = new HashSet<>();
            for (Room room : stale) if (room.pairingCode() != null) update("INSERT INTO pairing_cooldown(code,until_ms) VALUES(?,?) ON CONFLICT(code) DO UPDATE SET until_ms=MAX(until_ms,excluded.until_ms)",room.pairingCode(),now+1800000);
            for (Room room : stale) { String code = newCode(now,reserved); reserved.add(code); update("UPDATE rooms SET pair_code=?,pair_epoch=? WHERE id=?",code,period,room.id()); }
            update("DELETE FROM pairing_cooldown WHERE until_ms<=?",now);
    }
    public static long epoch(long now) { return Math.floorDiv(now,600000); }
    public synchronized long pairingEpoch(long now) { return pairingBaseEpoch+Math.floorDiv(now-pairingStartMs,pairingIntervalMinutes*60000L); }
    public synchronized long nextPairingUpdateAt(long now) { return pairingStartMs+(Math.floorDiv(now-pairingStartMs,pairingIntervalMinutes*60000L)+1)*pairingIntervalMinutes*60000L; }
    public synchronized int pairingIntervalMinutes() { return pairingIntervalMinutes; }
    public synchronized void setPairingOpen(String id, boolean open) { room(id);update("UPDATE rooms SET pairing_open=? WHERE id=?",open?1:0,id); }
    public synchronized void setShareEnabled(String id, boolean enabled) { room(id);update("UPDATE rooms SET share_enabled=? WHERE id=?",enabled?1:0,id); }
    public synchronized void setPublicRoom(String id,boolean publicRoom) {
        if(room(id).publicRoom()==publicRoom) return;
        update("UPDATE rooms SET public_room=?,access_version=access_version+1 WHERE id=?",publicRoom?1:0,id);
    }
    private String newCode(long now, Set<String> extra) {
        Set<String> unavailable = new HashSet<>(extra);
        for (Room r : rooms()) if (r.pairingCode() != null) unavailable.add(r.pairingCode());
        try (PreparedStatement s = db.prepareStatement("SELECT code FROM pairing_cooldown WHERE until_ms>?") ) {
            s.setLong(1,now); try (ResultSet r = s.executeQuery()) { while (r.next()) unavailable.add(r.getString(1)); }
        } catch (SQLException e) { throw storage(e); }
        SortedSet<Integer> blocked = new TreeSet<>();
        for (String code : unavailable) if (RoomValidation.pairingCode(code)) blocked.add(Integer.parseInt(code, 36));
        if (blocked.size() == CODE_SPACE) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"配对码暂不可分配，请稍后重试");
        // Select uniformly from the available space without materializing 36^4 strings.
        int selected = random.nextInt(CODE_SPACE - blocked.size());
        for (int value : blocked) { if (value > selected) break; selected++; }
        String code = Integer.toString(selected, 36);
        return "0".repeat(4 - code.length()) + code;
    }
    public synchronized String payload(String id, String column) {
        if (!Set.of("payload","config").contains(column)) throw new IllegalArgumentException();
        try (PreparedStatement s = db.prepareStatement("SELECT " + column + " FROM rooms WHERE id=?")) { s.setString(1,id); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; } } catch (SQLException e) { throw storage(e); }
    }
    public synchronized void save(String id, String payload, String config) { update("UPDATE rooms SET payload=?,config=? WHERE id=?",payload,config,id); }
    public synchronized String systemConfig() {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT payload FROM system_config WHERE id=1")) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) { throw storage(e); }
    }
    public synchronized void saveSystemConfig(String payload) {
        update("INSERT INTO system_config(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",payload);
    }
    public synchronized void saveSystemConfig(String payload, int intervalMinutes) {
        if (intervalMinutes<1 || intervalMinutes>60) throw new IllegalArgumentException("Invalid pairing interval");
        if (pairingIntervalMinutes==intervalMinutes) { saveSystemConfig(payload); return; }
        long now=System.currentTimeMillis();
        long resetEpoch=Math.max(pairingEpoch(now),rooms().stream().mapToLong(Room::pairingEpoch).max().orElse(0))+1;
        transaction(() -> {
            saveSystemConfig(payload);
            update("INSERT INTO pairing_clock(id,interval_minutes,start_ms,base_epoch) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET interval_minutes=excluded.interval_minutes,start_ms=excluded.start_ms,base_epoch=excluded.base_epoch",intervalMinutes,now,resetEpoch);
            rotateCodes(now,resetEpoch,true);
        });
        pairingIntervalMinutes=intervalMinutes;pairingStartMs=now;pairingBaseEpoch=resetEpoch;
    }
    public synchronized boolean consumeAutoOpen(String id) { boolean first = room(id).autoOpen(); if (first) update("UPDATE rooms SET auto_open=0 WHERE id=?",id); return first; }
    public synchronized License addLicense(String key) {
        return addLicense(key, "");
    }
    public synchronized License addLicense(String key, String note) {
        License value = new License("LICENSE-"+UUID.randomUUID().toString().substring(0,8),key,note);
        List<License> next = new ArrayList<>(licenses); next.add(value); commitLicenses(next); return value;
    }
    public synchronized void replaceLicense(String id, String key) {
        if (license(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"许可不存在");
        commitLicenses(licenses.stream().map(l -> l.id().equals(id) ? new License(id,key,l.note()) : l).toList());
    }
    public synchronized void updateLicenseNote(String id, String note) {
        if (license(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"许可不存在");
        commitLicenses(licenses.stream().map(l -> l.id().equals(id) ? new License(id,l.key(),note) : l).toList());
    }
    public synchronized List<String> removeLicense(String id) {
        if (license(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"许可不存在");
        List<String> associated = rooms().stream().filter(r -> r.ownerId().equals(id)).map(Room::id).toList();
        commitLicenses(licenses.stream().filter(l -> !l.id().equals(id)).toList());
        for (String room : associated) deleteRoom(room);
        return associated;
    }
    private void commitLicenses(List<License> next) { validateLicenses(next); try { writeLicenses(next); licenses = new ArrayList<>(next); } catch (Exception e) { throw storage(e); } }
    private void validateLicenses(List<License> list) {
        if (list == null) throw new IllegalStateException("License file must be a JSON list");
        Set<String> ids = new HashSet<>(), keys = new HashSet<>(Set.of(properties.getRootKey()));
        for (License l : list) {
            if (l == null || l.id() == null || !l.id().matches("[A-Za-z0-9_-]{1,64}") || "ROOT".equals(l.id()) || !ids.add(l.id()) || !RoomValidation.key(l.key()) || !keys.add(l.key())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"许可 ID 或密钥格式无效，ID 和密钥均不能重复");
            if (!RoomValidation.note(l.note())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"备注最多 16 个可见字符，不含换行或不可见控制字符");
        }
    }
    private void writeLicenses(List<License> list) throws Exception {
        Path file = Path.of(properties.getLicenseFile()).toAbsolutePath(); Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(),"licenses-",".tmp");
        try { Files.writeString(temp,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list)); try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temp); }
    }
    public synchronized String profile(String token) { try (PreparedStatement s=db.prepareStatement("SELECT payload FROM profiles WHERE token=?")) { s.setString(1,token); try (ResultSet r=s.executeQuery()) { return r.next()?r.getString(1):null; } } catch(SQLException e) { throw storage(e); } }
    public synchronized void saveProfile(String token,String payload) { update("INSERT INTO profiles(token,payload) VALUES(?,?) ON CONFLICT(token) DO UPDATE SET payload=excluded.payload",token,payload); }
    public synchronized void saveAdmission(RoomAccessService.Admission value) { update("INSERT INTO admissions(token,room_id,epoch,expires_at,public_entry,access_version) VALUES(?,?,?,?,?,?)",value.token(),value.roomId(),value.epoch(),value.expiresAt(),value.publicEntry()?1:0,value.accessVersion()); }
    public synchronized RoomAccessService.Admission admission(String token) {
        try (PreparedStatement s=db.prepareStatement("SELECT * FROM admissions WHERE token=?")) {
            s.setString(1,token);
            try (ResultSet r=s.executeQuery()) { return r.next()?new RoomAccessService.Admission(token,r.getString("room_id"),r.getLong("epoch"),r.getLong("expires_at"),r.getInt("public_entry")!=0,r.getLong("access_version")):null; }
        } catch(SQLException e) { throw storage(e); }
    }
    public synchronized void cleanupAdmissions(long now) { update("DELETE FROM admissions WHERE expires_at<?",now-1800000); }
    private void transaction(Runnable operation) {
        try { db.setAutoCommit(false); operation.run(); db.commit(); }
        catch(RuntimeException | SQLException e) { try {db.rollback();} catch(SQLException ignored) {} if(e instanceof RuntimeException runtime) throw runtime; throw storage(e); }
        finally { try {db.setAutoCommit(true);} catch(SQLException e) {throw storage(e);} }
    }
    private void update(String sql,Object... args) { try (PreparedStatement s = db.prepareStatement(sql)) { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); s.executeUpdate(); } catch (SQLException e) { throw storage(e); } }
    private RuntimeException storage(Exception e) { return new IllegalStateException("Multi-room data could not be saved",e); }
}
