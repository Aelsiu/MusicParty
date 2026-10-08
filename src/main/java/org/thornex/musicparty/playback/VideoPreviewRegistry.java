package org.thornex.musicparty.playback;

import org.thornex.musicparty.dto.VideoFavorites.*;
import java.time.*;
import java.util.*;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Room mailbox-owned preview cache. Owner is the verified HttpOnly browser identity. */
public final class VideoPreviewRegistry {
    public static final Duration TTL = Duration.ofMinutes(10);
    public record Context(String roomId, String playbackEpoch, long modeEpoch, String browserIdentity) {}
    private static final class Entry {
        final String id = UUID.randomUUID().toString(); final Context context; final Instant expiry;
        final Map<Integer,Page> pages = new HashMap<>(); final Map<String,Item> items = new HashMap<>();
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
        if (selections == null || selections.isEmpty()) throw failure("INVALID_SELECTION");
        Set<String> ids = new HashSet<>(); List<Selected> result = new ArrayList<>();
        for (Selection selection : selections) {
            Item item = selection == null ? null : entry.items.get(selection.itemId());
            if (item == null || !item.selectable() || !ids.add(item.itemId()) || selection.page() < 1 || selection.page() > 1000) throw failure("INVALID_SELECTION");
            result.add(new Selected(item.itemId(), item.bvid(), selection.page()));
        }
        return List.copyOf(result);
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
