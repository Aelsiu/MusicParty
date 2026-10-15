package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.dto.VideoFavorites;
import org.thornex.musicparty.util.BilibiliApiUtils;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.ArrayList;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Fixed metadata endpoint: never follows or fetches the submitted share URL. */
@Service @org.thornex.musicparty.room.RoomScoped
public class BilibiliVideoFavoritesService {
    private final WebClient client;
    private final BilibiliCookieService cookies;
    public BilibiliVideoFavoritesService(WebClient client, BilibiliCookieService cookies) { this.client = client; this.cookies = cookies; }
    public Mono<VideoFavorites.Page> page(String folderId, int page) {
        if (folderId == null || !folderId.matches("[1-9][0-9]{0,18}") || page < 1 || page > 25) return Mono.error(failure("INVALID_SELECTION"));
        if (!cookies.hasCookie()) return Mono.error(failure("CREDENTIALS_REQUIRED"));
        var uri = UriComponentsBuilder.fromHttpUrl("https://api.bilibili.com/x/v3/fav/resource/list")
                .queryParam("media_id", folderId).queryParam("pn", page).queryParam("ps", 20).build().toUri();
        return cookies.getCookieHeader().flatMap(cookie -> client.get().uri(uri).header("Cookie", cookie)
                .header("Referer", "https://www.bilibili.com/").header("User-Agent", "Mozilla/5.0")
                .retrieve().bodyToMono(JsonNode.class)).timeout(Duration.ofSeconds(20))
                .switchIfEmpty(Mono.error(failure("TEMPORARY_FAILURE"))).map(json -> parse(json, folderId, page))
                .onErrorMap(error -> error instanceof VideoFavorites.Failure ? error
                        : error instanceof WebClientResponseException response && response.getStatusCode().value() == 401
                        ? failure("CREDENTIALS_INVALID") : failure("TEMPORARY_FAILURE"));
    }
    public static VideoFavorites.Page parse(JsonNode root, String folderId, int page) {
        if (!root.path("code").isInt()) throw failure("TEMPORARY_FAILURE");
        int code = root.path("code").asInt();
        if (code != 0) throw failure(switch(code) { case -101, -111 -> "CREDENTIALS_INVALID"; case -403, -404, 62002, 62012 -> "CONTENT_UNAVAILABLE"; default -> "TEMPORARY_FAILURE"; });
        JsonNode data = root.path("data");
        if (!data.isObject() || !data.path("info").isObject() || !data.has("has_more")) throw failure("TEMPORARY_FAILURE");
        JsonNode more = data.path("has_more");
        if (!more.isBoolean() && !(more.isIntegralNumber() && (more.asLong() == 0 || more.asLong() == 1))) throw failure("TEMPORARY_FAILURE");
        ArrayList<VideoFavorites.Item> items = new ArrayList<>();
        JsonNode medias = data.path("medias");
        if (!medias.isNull() && !medias.isMissingNode() && !medias.isArray()) throw failure("TEMPORARY_FAILURE");
        if (medias.isArray()) {
            if (medias.size() > 20) throw failure("TEMPORARY_FAILURE");
            int offset = (page - 1) * 20;
            for (JsonNode media : medias) {
                String bvid = media.path("bvid").asText("");
                String title = media.path("title").asText("");
                boolean ordinary = media.path("type").asInt(-1) == 2;
                boolean valid = bvid.matches("BV[A-Za-z0-9]{10}") && !title.equals("已失效视频") && media.path("attr").asInt() != 9;
                String reason = !ordinary ? "UNSUPPORTED_RESOURCE" : !valid ? "CONTENT_UNAVAILABLE" : null;
                items.add(new VideoFavorites.Item((offset + items.size() + 1) + ":" + media.path("id").asText("unknown"),
                        valid && ordinary ? bvid : null, title, BilibiliApiUtils.normalizeCoverUrl(media.path("cover").asText("")),
                        media.path("upper").path("name").asText(""), Math.max(0, media.path("duration").asLong()) * 1000, reason == null, reason));
            }
        }
        return new VideoFavorites.Page(folderId, data.path("info").path("title").asText(""), page, items, more.isBoolean() ? more.asBoolean() : more.asLong() == 1);
    }
}
