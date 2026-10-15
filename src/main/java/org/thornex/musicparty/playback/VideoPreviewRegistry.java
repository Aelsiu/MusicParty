package org.thornex.musicparty.playback;

import org.thornex.musicparty.dto.VideoFavorites.*;
import java.time.*;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Room mailbox-owned preview cache. Owner is the verified HttpOnly browser identity. */
public final class VideoPreviewRegistry {
    public enum Type { FAVORITES, VIDEO_PARTS, PGC_EPISODES, XIFAN_EPISODES }
    public static final Duration TTL = Duration.ofMinutes(10);
    public record Context(String roomId, String playbackEpoch, long modeEpoch, String browserIdentity) {}
    private static final class Entry {
        final String id = UUID.randomUUID().toString(); final Context context; final Instant expiry;
        final Map<Integer,Page> pages = new HashMap<>(); final Map<String,Item> items = new HashMap<>();
        Type type = Type.FAVORITES;
        org.thornex.musicparty.dto.VideoDescriptor descriptor;
        org.thornex.musicparty.service.api.BilibiliPgcApiService.Season season;
        long cookieRevision;
        org.thornex.musicparty.dto.XifanVideo.Detail xifan;
        long sourceId;
        Entry(Context context, Instant now) { this.context = context; this.expiry = now.plus(TTL); }
    }
    private final Map<String,Entry> entries = new HashMap<>();
    public String create(Context context, Instant now) {
        purge(now);
        if (context.browserIdentity() == null) throw failure("PREVIEW_NOT_FOUND");
        entries.values().removeIf(entry -> entry.context.browserIdentity().equals(context.browserIdentity()));
        if (entries.size() >= 16) throw failure("IMPORT_BUSY");
        Entry entry = new Entry(context, now); entries.put(entry.id, entry); return entry.id;
    }
    public Preview cached(String id, Context context, int page, Instant now) {
        Entry entry = require(id, context, now); Page loaded = entry.pages.get(page);
        return loaded == null ? null : response(entry, loaded);
    }
    public Preview put(String id, Context context, Page page, Instant now) {
        Entry entry = require(id, context, now);
        if (page.page() < 1 || page.page() > 25 || page.items().size() > 20) throw failure("INVALID_SELECTION");
        // The first loaded representation is immutable even if the upstream folder changes.
        Page original = entry.pages.putIfAbsent(page.page(), page);
        Page accepted = original == null ? page : original;
        if (original == null) accepted.items().forEach(item -> entry.items.put(item.itemId(), item));
        return response(entry, accepted);
    }
    public List<Selected> select(String id, Context context, List<Selection> selections, Instant now) {
        Entry entry = require(id, context, now);
        if (entry.type != Type.FAVORITES) throw failure("INVALID_SELECTION");
        if (selections == null || selections.isEmpty()) throw failure("INVALID_SELECTION");
        Set<String> ids = new HashSet<>(); List<Selected> result = new ArrayList<>();
        for (Selection selection : selections) {
            Item item = selection == null ? null : entry.items.get(selection.itemId());
            if (item == null || !item.selectable() || !ids.add(item.itemId()) || selection.page() < 1 || selection.page() > 1000) throw failure("INVALID_SELECTION");
            result.add(new Selected(item.itemId(), item.bvid(), selection.page()));
        }
        return List.copyOf(result);
    }
    public String createParts(Context context, org.thornex.musicparty.dto.VideoDescriptor descriptor, long cookieRevision, Instant now) {
        if (descriptor.pages().isEmpty() || descriptor.pages().size() > 1000) throw failure("INVALID_SELECTION");
        String id = create(context, now);
        Entry entry = entries.get(id); entry.type = Type.VIDEO_PARTS; entry.descriptor = descriptor; entry.cookieRevision = cookieRevision;
        return id;
    }
    public Instant expiresAt(String id, Context context, Instant now) { return require(id, context, now).expiry; }
    public String createEpisodes(Context context, org.thornex.musicparty.service.api.BilibiliPgcApiService.Season season,
                                 long cookieRevision, Instant now) {
        if(season.episodes().isEmpty() || season.episodes().size()>500) throw failure("INVALID_SELECTION");
        String id=create(context,now); Entry entry=entries.get(id); entry.type=Type.PGC_EPISODES;
        entry.season=season;entry.cookieRevision=cookieRevision; return id;
    }
    public List<Selected> selectEpisodes(String id,Context context,List<Selection> selections,long revision,Instant now) {
        Entry entry=require(id,context,now);
        if(entry.type!=Type.PGC_EPISODES || entry.cookieRevision!=revision) throw failure("PREVIEW_NOT_FOUND");
        if(selections==null || selections.isEmpty()) throw failure("INVALID_SELECTION");
        Set<String> ids=new HashSet<>();
        for(var item:selections) if(item==null || !ids.add(item.itemId()) || item.page()!=0
            || entry.season.episodes().stream().noneMatch(ep -> ep.itemId().equals(item.itemId()) && ep.selectable())) throw failure("INVALID_SELECTION");
        return entry.season.episodes().stream().filter(ep -> ids.contains(ep.itemId())).map(ep -> {
            var d=entry.season.descriptors().get(ep.canonicalId());
            if(d==null) throw failure("INVALID_SELECTION");
            return new Selected(ep.itemId(),d.bvid(),0,d.canonicalId());
        }).toList();
    }
    public org.thornex.musicparty.dto.VideoDescriptor episode(String id,Context context,Selected selected,long revision,Instant now) {
        selectEpisodes(id,context,List.of(new Selection(selected.itemId(),0)),revision,now);
        return require(id,context,now).season.descriptors().get(selected.canonicalId());
    }
    public List<Selected> selectParts(String id, Context context, List<Selection> selections, long cookieRevision, Instant now) {
        Entry entry = require(id, context, now);
        if (entry.type != Type.VIDEO_PARTS || entry.cookieRevision != cookieRevision) throw failure("PREVIEW_NOT_FOUND");
        if (selections == null || selections.isEmpty()) throw failure("INVALID_SELECTION");
        Set<String> ids = new HashSet<>(); List<Selected> result = new ArrayList<>();
        for (Selection selection : selections) {
            var page = selection == null ? null : entry.descriptor.pages().stream().filter(p -> p.page() == selection.page() && p.canonicalId().equals(selection.itemId())).findFirst().orElse(null);
            if (page == null || !page.withinDurationLimit() || !ids.add(selection.itemId())) throw failure("INVALID_SELECTION");
            result.add(new Selected(selection.itemId(), entry.descriptor.bvid(), selection.page()));
        }
        result.sort(Comparator.comparingInt(Selected::page)); return List.copyOf(result);
    }
    public org.thornex.musicparty.dto.VideoDescriptor part(String id, Context context, Selected selected, long cookieRevision, Instant now) {
        selectParts(id, context, List.of(new Selection(selected.itemId(), selected.page())), cookieRevision, now);
        var d = require(id, context, now).descriptor;
        var p = d.pages().stream().filter(value -> value.page() == selected.page()).findFirst().orElseThrow();
        return new org.thornex.musicparty.dto.VideoDescriptor(p.canonicalId(), d.bvid(), p.cid(), p.page(), d.title(), p.title(), d.ownerName(), p.durationMs(), d.coverUrl(), d.pages());
    }
    public void clearBilibili(){entries.values().removeIf(e->e.type!=Type.XIFAN_EPISODES);}
    public String createXifan(Context context,org.thornex.musicparty.dto.XifanVideo.Detail detail,long sourceId,long revision,Instant now){
        if(detail.sources().stream().noneMatch(s->s.id()==sourceId))throw failure("INVALID_SELECTION");
        String id=create(context,now);Entry e=entries.get(id);e.type=Type.XIFAN_EPISODES;e.xifan=detail;e.sourceId=sourceId;e.cookieRevision=revision;return id;
    }
    public List<Selected> selectXifan(String id,Context context,List<Selection> selections,long revision,Instant now){
        Entry e=require(id,context,now);if(e.type!=Type.XIFAN_EPISODES || e.cookieRevision!=revision || selections==null || selections.isEmpty())throw failure("INVALID_SELECTION");
        var route=e.xifan.sources().stream().filter(s->s.id()==e.sourceId).findFirst().orElseThrow();Set<String> ids=new HashSet<>();
        for(var item:selections)if(item==null || item.page()!=0 || !ids.add(item.itemId()) || route.episodes().stream().noneMatch(ep->ep.itemId().equals(item.itemId())))throw failure("INVALID_SELECTION");
        return route.episodes().stream().filter(ep->ids.contains(ep.itemId())).map(ep->new Selected(ep.itemId(),null,0,ep.itemId())).toList();
    }
    public org.thornex.musicparty.dto.VideoSelection xifanSelection(String id,Context context,Selected selected,long revision,Instant now){
        selectXifan(id,context,List.of(new Selection(selected.itemId(),0)),revision,now);Entry e=require(id,context,now);
        return new org.thornex.musicparty.dto.VideoSelection("xifan",e.xifan.anime().id(),org.thornex.musicparty.dto.VideoIdentity.positiveId(selected.itemId().substring(8)),e.sourceId);
    }
    public void clear() { entries.clear(); }
    private Entry require(String id, Context context, Instant now) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.context.equals(context)) throw failure("PREVIEW_NOT_FOUND");
        if (!now.isBefore(entry.expiry)) { entries.remove(id); throw failure("PREVIEW_EXPIRED"); }
        return entry;
    }
    private void purge(Instant now) { entries.values().removeIf(entry -> !now.isBefore(entry.expiry)); }
    private Preview response(Entry entry, Page page) {
        boolean truncated = page.page() == 25 && page.hasMore();
        boolean more = page.hasMore() && page.page() < 25;
        return new Preview(entry.id, page.folderId(), page.title(), page.page(), page.items(), more, more ? page.page() + 1 : null, entry.expiry, truncated);
    }
}
