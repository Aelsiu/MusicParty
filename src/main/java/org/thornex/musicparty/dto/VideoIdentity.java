package org.thornex.musicparty.dto;

import java.util.regex.Pattern;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Stable playback identities, never inferred from a BV page number. */
public record VideoIdentity(String contentType, String canonicalId, long epId) {
    private static final Pattern UGC = Pattern.compile("BV[A-Za-z0-9]{10}:p[1-9][0-9]{0,3}");
    public static final long MAX_SAFE_ID = 9007199254740991L;
    public static VideoIdentity parse(String value) {
        if (value != null && UGC.matcher(value).matches()) return new VideoIdentity("UGC", value, 0);
        if (value != null && value.matches("bilibili:ep[1-9][0-9]*")) {
            long ep = positiveId(value.substring(11));
            return new VideoIdentity("PGC", "bilibili:ep" + ep, ep);
        }
        if(value!=null && value.matches("xifan:ep[1-9][0-9]*")) { long id=positiveId(value.substring(8));return new VideoIdentity("XIFAN_EPISODE","xifan:ep"+id,0); }
        throw failure("INVALID_VIDEO_INPUT");
    }
    public static long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw failure("INVALID_VIDEO_INPUT");
        try {
            long id = Long.parseLong(value);
            if (id > MAX_SAFE_ID) throw failure("INVALID_VIDEO_INPUT");
            return id;
        } catch (NumberFormatException invalid) { throw failure("INVALID_VIDEO_INPUT"); }
    }
    public String sourcePage() {
        if(contentType.equals("XIFAN_EPISODE"))throw failure("INVALID_SELECTION");
        return contentType.equals("PGC") ? "https://www.bilibili.com/bangumi/play/ep" + epId
            : "https://www.bilibili.com/video/" + canonicalId.replace(":p", "?p=");
    }
}
