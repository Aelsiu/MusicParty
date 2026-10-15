package org.thornex.musicparty.playback;

import org.thornex.musicparty.dto.VideoFavorites.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Actor-owned ledger; network callbacks must return to the room mailbox before mutation. */
public final class VideoImportRegistry {
    public static final Duration RESULT_TTL = Duration.ofMinutes(10);
    public record Owner(String roomId, String playbackEpoch, String browserIdentity, String memberToken) {}
    public record Key(Owner owner, String requestId) {}
    public record Parameters(String previewId, List<Selection> selections, VideoPreviewRegistry.Type type) {
        public Parameters(String previewId, List<Selection> selections) { this(previewId, selections, VideoPreviewRegistry.Type.FAVORITES); }
        public Parameters { selections = List.copyOf(selections); }
    }
    public record Acceptance(Entry entry, boolean existing) {}
    public static final class Entry {
        private final Key key; private final Parameters parameters; private final String sessionId;
        private final String id = UUID.randomUUID().toString(); private final long modeEpoch;
        private final Instant acceptedAt; private final List<Selected> selections;
        private Operation operation;
        private Entry(Key key, Parameters parameters, String sessionId, long modeEpoch, Instant now, List<Selected> selected) {
            this.key = key; this.parameters = parameters; this.sessionId = sessionId; this.modeEpoch = modeEpoch;
            this.acceptedAt = now; this.selections = List.copyOf(selected);
            operation = snapshot("ACCEPTED", 1, null, selected.stream().map(item -> new ItemResult(item.itemId(), null, "PENDING", null, false, null)).toList());
        }
        public Parameters parameters(){return parameters;}
        public Key key() { return key; } public String sessionId() { return sessionId; }
        public List<Selected> selections() { return selections; } public Operation operation() { return operation; }
        public boolean active() { return operation.resultExpiresAt() == null; }
        private Operation snapshot(String status, long revision, Instant expiry, List<ItemResult> items) {
            int pending=0, added=0, skipped=0, failed=0, cancelled=0;
            for (ItemResult item : items) switch (item.status()) {
                case "PENDING" -> pending++; case "ADDED" -> added++; case "CANCELLED" -> cancelled++;
                case "DUPLICATE", "QUOTA_EXCEEDED", "DURATION_EXCEEDED", "INVALID_SELECTION" -> skipped++;
                default -> failed++;
            }
            Owner owner=key.owner();
            return new Operation(3, owner.roomId(), owner.playbackEpoch(), "VIDEO", modeEpoch, key.requestId(), id,
                    revision, status, acceptedAt, expiry, new Counts(selections.size(),pending,added,skipped,failed,cancelled), items);
        }
    }
    private final Map<Key,Entry> entries = new LinkedHashMap<>();
    public Entry existing(Key key, Parameters parameters, Instant now) {
        purge(now); Entry entry=entries.get(key);
        if (entry != null && !entry.parameters.equals(parameters)) throw failure("REQUEST_ID_CONFLICT");
        return entry;
    }
    public Acceptance reserve(Key key, Parameters parameters, String sessionId, long modeEpoch, List<Selected> selected, Instant now) {
        Entry old=existing(key,parameters,now); if (old != null) return new Acceptance(old,true);
        if (key.requestId()==null || key.requestId().isBlank() || key.requestId().length()>128 || selected.isEmpty()) throw failure("INVALID_SELECTION");
        long active=entries.values().stream().filter(Entry::active).count();
        if (entries.size()>=32 || active>=8 || entries.values().stream().anyMatch(entry -> entry.active() && entry.key.owner().equals(key.owner()))) throw failure("IMPORT_BUSY");
        Entry entry=new Entry(key,parameters,sessionId,modeEpoch,now,selected); entries.put(key,entry); return new Acceptance(entry,false);
    }
    /** Rollback only before acceptance has been published, e.g. completion ticket exhaustion. */
    public void rollback(Entry entry) { if (entry.operation.operationRevision()==1) entries.remove(entry.key,entry); }
    public Operation resolving(Entry entry) {
        require(entry); if(entry.active()) entry.operation=entry.snapshot("RESOLVING",entry.operation.operationRevision()+1,null,entry.operation.items()); return entry.operation;
    }
    public Operation finish(Entry entry, List<ItemResult> results, Instant now) {
        require(entry); if(!entry.active()) return entry.operation;
        if(results.size()!=entry.selections.size()) throw new IllegalArgumentException("Every selection requires a terminal result");
        for(int i=0;i<results.size();i++) if(!results.get(i).itemId().equals(entry.selections.get(i).itemId()) || results.get(i).status().equals("PENDING")) throw new IllegalArgumentException("Invalid terminal selection order");
        long added=results.stream().filter(item -> item.status().equals("ADDED")).count();
        String status=added==results.size()?"COMPLETED":added>0?"PARTIAL":"FAILED";
        entry.operation=entry.snapshot(status,entry.operation.operationRevision()+1,now.plus(RESULT_TTL),results); return entry.operation;
    }
    public List<Entry> cancel(Predicate<Entry> predicate, Instant now) {
        List<Entry> cancelled=new ArrayList<>();
        for(Entry entry:entries.values()) if(entry.active() && predicate.test(entry)) {
            entry.operation=entry.snapshot("CANCELLED",entry.operation.operationRevision()+1,now.plus(RESULT_TTL),
                    entry.selections.stream().map(item -> new ItemResult(item.itemId(),null,"CANCELLED","CANCELLED",true,null)).toList()); cancelled.add(entry);
        }
        return List.copyOf(cancelled);
    }
    public Operation status(Owner owner, String operationId, String originalRequestId, Instant now) {
        purge(now);
        return entries.values().stream().filter(entry -> entry.key.owner().equals(owner)
                && (operationId != null ? entry.id.equals(operationId) : entry.key.requestId().equals(originalRequestId)))
                .findFirst().map(Entry::operation).orElseThrow(() -> failure("IMPORT_RESULT_UNAVAILABLE"));
    }
    private void require(Entry entry) { if(entries.get(entry.key)!=entry) throw failure("IMPORT_RESULT_UNAVAILABLE"); }
    private void purge(Instant now) { entries.values().removeIf(entry -> !entry.active() && !now.isBefore(entry.operation.resultExpiresAt())); }
}
