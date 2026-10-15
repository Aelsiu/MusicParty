package org.thornex.musicparty.util;

import java.net.URI;
import java.time.Instant;

/** Reads only the upstream's observed deadline field; never assumes a fixed URL lifetime. */
public final class VideoSourceExpiry {
    private VideoSourceExpiry() {}

    public static Instant deadline(String url) {
        if (url == null) return null;
        try {
            String query = URI.create(url).getRawQuery();
            if (query == null) return null;
            Instant deadline = null;
            boolean found = false;
            for (String part : query.split("&")) {
                if (!part.startsWith("deadline=")) continue;
                if (found) return null;
                found = true;
                String value = part.substring("deadline=".length());
                if (!value.matches("[0-9]{1,12}")) return null;
                long seconds = Long.parseLong(value);
                if (seconds < 946684800L) return null;
                deadline = Instant.ofEpochSecond(seconds);
            }
            return deadline;
        } catch (RuntimeException ignored) { return null; }
    }
}
