package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Fixed official origins; login responses and candidate credentials never enter logs or browser state. */
@Service
public class BilibiliQrLoginClient {
    private static final String PASSPORT = "https://passport.bilibili.com";
    private static final String API = "https://api.bilibili.com";
    private static final Map<String, Set<String>> QR_LOCATIONS = Map.of(
            "passport.bilibili.com", Set.of("/h5-app/passport/login/scan", "/qrcode/h5/login"),
            "account.bilibili.com", Set.of("/h5/account-h5/auth/scan-web"));
    private static final Set<String> COOKIE_NAMES = Set.of("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "buvid4", "b_nut");
    private static final Set<String> REQUIRED = Set.of("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5");
    private final WebClient web;
    public record QrCode(String key, String url) { @Override public String toString() { return "QrCode[redacted]"; } }
    public record Poll(String state, String cookie) { @Override public String toString() { return "Poll[state=" + state + "]"; } }
    private record Reply(JsonNode body, Map<String,String> cookies) {}

    public BilibiliQrLoginClient(WebClient web) {
        this.web = web.mutate().codecs(c -> c.defaultCodecs().maxInMemorySize(65536)).build();
    }
    public QrCode create() {
        JsonNode root = request(PASSPORT + "/x/passport-login/web/qrcode/generate", null).body();
        String key = root.path("data").path("qrcode_key").asText();
        String url = root.path("data").path("url").asText();
        if (root.path("code").asInt(-1) != 0) throw unavailable();
        if (!key.matches("[a-fA-F0-9]{32}") || !trustedQr(url, key))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站二维码格式暂不受支持，请更新启动器后重试");
        return new QrCode(key, url);
    }
    private boolean trustedQr(String value, String key) {
        try {
            URI uri = URI.create(value);
            List<String> keys = Arrays.stream(Optional.ofNullable(uri.getRawQuery()).orElse("").split("&"))
                    .filter(part -> part.startsWith("qrcode_key=")).toList();
            return value.length() <= 2048 && "https".equals(uri.getScheme())
                    && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getFragment() == null
                    && QR_LOCATIONS.getOrDefault(uri.getHost(), Set.of()).contains(uri.getPath())
                    && keys.size() == 1 && keys.getFirst().equals("qrcode_key=" + key);
        } catch (RuntimeException e) { return false; }
    }
    public Poll poll(String key) {
        if (key == null || !key.matches("[a-fA-F0-9]{32}")) throw unavailable();
        Reply reply = request(PASSPORT + "/x/passport-login/web/qrcode/poll?qrcode_key=" + key, null);
        if (reply.body().path("code").asInt(-1) != 0) throw unavailable();
        int status = reply.body().path("data").path("code").asInt(-1);
        String state = switch (status) { case 86101 -> "WAITING"; case 86090 -> "CONFIRMING"; case 86038 -> "EXPIRED"; case 0 -> "VALIDATING"; default -> "FAILED"; };
        if (status != 0) return new Poll(state, null);
        Map<String,String> cookies = new LinkedHashMap<>(reply.cookies());
        if (!REQUIRED.stream().allMatch(name -> safeValue(cookies.get(name)))) return new Poll("FAILED", null);
        // Obtain fresh anonymous device fields; do not merge the old account's Cookie into this login.
        JsonNode fingerprint = request(API + "/x/frontend/finger/spi", null).body();
        if (fingerprint.path("code").asInt(-1) != 0 || !safeValue(fingerprint.path("data").path("b_3").asText())) return new Poll("FAILED", null);
        cookies.put("buvid3", fingerprint.path("data").path("b_3").asText());
        String b4 = fingerprint.path("data").path("b_4").asText();
        if (safeValue(b4)) cookies.put("buvid4", b4);
        cookies.put("b_nut", Long.toString(System.currentTimeMillis() / 1000));
        String cookie = String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        JsonNode nav = request(API + "/x/web-interface/nav", cookie).body();
        if (nav.path("code").asInt(-1) != 0 || !nav.path("data").path("isLogin").asBoolean()
                || !nav.path("data").path("mid").asText().equals(cookies.get("DedeUserID"))) return new Poll("FAILED", null);
        return new Poll("VALIDATING", cookie);
    }
    private static boolean safeValue(String value) {
        return value != null && !value.isBlank() && value.length() <= 4096 && value.chars().allMatch(c -> c > 32 && c < 127 && c != ';' && c != ',');
    }
    private Reply request(String url, String cookie) {
        try {
            var request = web.get().uri(url).header("Referer", "https://www.bilibili.com/");
            if (cookie != null) request.header("Cookie", cookie);
            Reply result = request.exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(reactor.core.publisher.Mono.error(unavailable()));
                Map<String,String> cookies = new LinkedHashMap<>();
                response.cookies().forEach((name, values) -> {
                    if (COOKIE_NAMES.contains(name) && !values.isEmpty()) {
                        String value = values.getLast().getValue();
                        if (safeValue(value)) cookies.put(name, value);
                    }
                });
                return response.bodyToMono(JsonNode.class).map(body -> new Reply(body, cookies));
            }).block(Duration.ofSeconds(8));
            if (result == null) throw unavailable();
            return result;
        } catch (RuntimeException e) { throw unavailable(); }
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站登录服务暂不可用，请重试"); }
}
