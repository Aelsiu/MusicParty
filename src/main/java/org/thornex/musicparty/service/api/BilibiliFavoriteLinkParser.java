package org.thornex.musicparty.service.api;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

public final class BilibiliFavoriteLinkParser {
    private BilibiliFavoriteLinkParser() {}
    public static String parse(String link) {
        try {
            if (link == null || link.length() > 4096) throw new IllegalArgumentException();
            URI uri = URI.create(link.trim());
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            Map<String,String> query = new HashMap<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&", -1)) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if ((key.equals("fid") || key.equals("business") || key.equals("business_id")) && query.containsKey(key)) throw new IllegalArgumentException();
                query.put(key, value);
            }
            String id;
            if ("space.bilibili.com".equals(uri.getHost()) && uri.getRawPath().matches("/[1-9][0-9]{0,18}/favlist")) id = query.get("fid");
            else if ("www.bilibili.com".equals(uri.getHost()) && uri.getRawPath().matches("/medialist/play/[1-9][0-9]{0,18}") && "space".equals(query.get("business"))) id = query.get("business_id");
            else throw new IllegalArgumentException();
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw failure("INVALID_FAVORITE_LINK"); }
    }
}
