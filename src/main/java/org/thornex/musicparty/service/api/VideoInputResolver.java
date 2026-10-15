package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import org.thornex.musicparty.dto.VideoDescriptor;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Input classification and anonymous, allowlisted short-link expansion. Never fetches landing pages. */
@Service @org.thornex.musicparty.room.RoomScoped
public class VideoInputResolver {
    public record Input(String type, String value, Integer explicitPage) {}
    public record Result(Input input, VideoDescriptor video, BilibiliPgcApiService.Season season) {
        public Result(Input input,VideoDescriptor video) { this(input,video,null); }
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private BilibiliPgcApiService pgc;
    private static final Pattern BARE = Pattern.compile("(BV[A-Za-z0-9]{10})(?::p([1-9][0-9]{0,3}))?");
    private static final Pattern AV = Pattern.compile("(?i)av([1-9][0-9]{0,18})");
    private static final Pattern URL = Pattern.compile("(?i)(?:https?://[^\\s<>\"“”]+|(?<![a-z0-9.])b23\\.tv/[^\\s<>\"“”]+)");
    private static final Set<String> HOSTS = Set.of("b23.tv", "www.bilibili.com", "bilibili.com", "space.bilibili.com");
    private final BilibiliVideoApiService videos;
    private final WebClient anonymous;
    @org.springframework.beans.factory.annotation.Autowired
    public VideoInputResolver(BilibiliVideoApiService videos) {
        this.videos = videos;
        anonymous = org.thornex.musicparty.config.WebClientConfig.anonymousShortLinks();
    }
    VideoInputResolver(BilibiliVideoApiService videos, WebClient anonymous) { this.videos = videos; this.anonymous = anonymous; }
    public Mono<Result> resolve(String raw) {
        return Mono.defer(() -> {
            Input input = classify(raw);
            Mono<Input> normalized = input.type().equals("SHORT") ? expand(URI.create(input.value()), input.explicitPage(), 0, new HashSet<>()).timeout(Duration.ofSeconds(8)) : Mono.just(input);
            return normalized.flatMap(value -> {
                if(value.type().startsWith("PGC_")) {
                    if(pgc==null) return Mono.error(failure("PLATFORM_NOT_AVAILABLE"));
                    boolean episode=value.type().equals("PGC_EPISODE");
                    long id=org.thornex.musicparty.dto.VideoIdentity.positiveId(value.value().substring(2));
                    return pgc.season(id,episode).map(season -> new Result(value,
                        episode?season.descriptors().get("bilibili:ep"+id):null,season));
                }
                if (!value.type().equals("VIDEO")) return Mono.just(new Result(value, null));
                Mono<VideoDescriptor> metadata = value.value().startsWith("av") ? videos.getAvMetadata(value.value().substring(2)) : videos.getVideoMetadata(value.value());
                return metadata.flatMap(descriptor -> {
                    if("PGC".equals(descriptor.contentType())) {
                        if(value.explicitPage()!=null) throw failure("PAGE_CONFLICT");
                        if(pgc==null) return Mono.error(failure("PLATFORM_NOT_AVAILABLE"));
                        return pgc.season(descriptor.epId(),true).map(season -> new Result(new Input("PGC_EPISODE","ep"+descriptor.epId(),null),descriptor,season));
                    }
                    if (value.explicitPage() != null && descriptor.pages().stream().noneMatch(p -> p.page() == value.explicitPage())) throw failure("INVALID_VIDEO_INPUT");
                    return Mono.just(new Result(value, descriptor));
                });
            });
        }).timeout(Duration.ofSeconds(30)).onErrorMap(error -> {
            if (error instanceof org.thornex.musicparty.dto.VideoFavorites.Failure) return error;
            if (error instanceof BilibiliVideoApiService.VideoSourceException source) return failure(switch (source.getCode()) {
                case CREDENTIALS_MISSING -> "CREDENTIALS_REQUIRED"; case CREDENTIALS_INVALID -> "CREDENTIALS_INVALID";
                case CONTENT_UNAVAILABLE -> "CONTENT_UNAVAILABLE"; case UNSUPPORTED_INPUT -> "INVALID_VIDEO_INPUT";
                default -> "TEMPORARY_FAILURE";
            });
            return failure("TEMPORARY_FAILURE");
        });
    }
    public static Input classify(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 4096) throw failure("INVALID_VIDEO_INPUT");
        String text = raw.trim(); Matcher bare = BARE.matcher(text);
        Matcher targets=Pattern.compile("(?<![A-Za-z0-9])(?:ss|ep)[1-9][0-9]*(?![A-Za-z0-9])").matcher(text);
        Set<String> explicitTargets=new HashSet<>();while(targets.find()) explicitTargets.add(targets.group());
        if(explicitTargets.size()>1) throw failure("AMBIGUOUS_VIDEO_INPUT");
        if(text.matches("(?:ss|ep)[1-9][0-9]*")) {
            long id=org.thornex.musicparty.dto.VideoIdentity.positiveId(text.substring(2));
            return new Input(text.startsWith("ss")?"PGC_SEASON":"PGC_EPISODE",text.substring(0,2)+id,null);
        }
        if(text.startsWith("bilibili:ep")) {
            var id=org.thornex.musicparty.dto.VideoIdentity.parse(text);
            return new Input("PGC_EPISODE","ep"+id.epId(),null);
        }
        if (bare.matches()) return new Input("VIDEO", bare.group(1), bare.group(2) == null ? null : page(bare.group(2)));
        Matcher av = AV.matcher(text);
        if (av.matches()) return new Input("VIDEO", "av" + av.group(1), null);
        Matcher urls = URL.matcher(text); String link = null;
        while (urls.find()) { if (link != null) throw failure("AMBIGUOUS_VIDEO_INPUT"); link = urls.group(); }
        if (link == null) {
            if (text.matches("(?i)^(?:av|ss|ep)[0-9+\\-].*") || text.matches("^BV[A-Za-z0-9]+(?::.*)?$") || text.contains("://")) throw failure("INVALID_VIDEO_INPUT");
            return new Input("KEYWORD", text, null);
        }
        // Punctuation outside a share URL is not part of the trusted address.
        link = link.replaceAll("[。，、！!）)\\]】；;]+$", "");
        if (link.regionMatches(true, 0, "b23.tv/", 0, 7)) link = "https://" + link;
        if (link.regionMatches(true, 0, "http://b23.tv/", 0, 14)) link = "https://" + link.substring(7);
        return address(link);
    }
    private static URI checked(String value) {
        try {
            URI uri = URI.create(value); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))) throw failure("INVALID_VIDEO_INPUT");
            return uri;
        } catch (IllegalArgumentException e) { throw failure("INVALID_VIDEO_INPUT"); }
    }
    private static Input address(String value) {
        URI uri = checked(value); String host = uri.getHost().toLowerCase(Locale.ROOT);
        Matcher pgc=Pattern.compile("/bangumi/play/(ss|ep)([1-9][0-9]*)/?").matcher(uri.getRawPath());
        if((host.equals("www.bilibili.com") || host.equals("bilibili.com")) && pgc.matches()) {
            long id=org.thornex.musicparty.dto.VideoIdentity.positiveId(pgc.group(2));
            explicit(uri); // Reject duplicated/invalid explicit p; valid p never selects an EP.
            return new Input(pgc.group(1).equals("ss")?"PGC_SEASON":"PGC_EPISODE",pgc.group(1)+id,null);
        }
        if (host.equals("b23.tv")) {
            if (!uri.getPath().matches("/[A-Za-z0-9]+/?")) throw failure("INVALID_VIDEO_INPUT");
            return new Input("SHORT", uri.toString(), explicit(uri));
        }
        Matcher video = Pattern.compile("/video/(BV[A-Za-z0-9]{10}|(?i:av[1-9][0-9]{0,18}))/?").matcher(uri.getPath());
        if ((host.equals("www.bilibili.com") || host.equals("bilibili.com")) && video.matches()) {
            String id = video.group(1); if (id.regionMatches(true, 0, "av", 0, 2)) id = "av" + id.substring(2);
            return new Input("VIDEO", id, explicit(uri));
        }
        BilibiliFavoriteLinkParser.parse(value);
        return new Input("FAVORITES", value, null);
    }
    private static Integer explicit(URI uri) {
        Integer result = null; boolean seen = false;
        if (uri.getRawQuery() == null) return null;
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("p")) {
                if (seen || pair.length != 2) throw failure("INVALID_VIDEO_INPUT");
                seen = true; result = page(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }
    private static int page(String value) {
        if (!value.matches("[1-9][0-9]{0,3}")) throw failure("INVALID_VIDEO_INPUT");
        int page = Integer.parseInt(value); if (page > 1000) throw failure("INVALID_VIDEO_INPUT"); return page;
    }
    private Mono<Input> expand(URI uri, Integer selected, int hops, Set<String> visited) {
        checked(uri.toString());
        Input current = address(uri.toString());
        if (!current.type().equals("SHORT")) {
            if(current.type().startsWith("PGC_") && selected!=null) return Mono.error(failure("PAGE_CONFLICT"));
            if (!current.type().equals("VIDEO")) return Mono.just(current);
            if (selected != null && current.explicitPage() != null && !selected.equals(current.explicitPage())) return Mono.error(failure("PAGE_CONFLICT"));
            return Mono.just(new Input(current.type(), current.value(), current.explicitPage() != null ? current.explicitPage() : selected));
        }
        if (selected != null && current.explicitPage() != null && !selected.equals(current.explicitPage())) return Mono.error(failure("PAGE_CONFLICT"));
        Integer nextPage = selected != null ? selected : current.explicitPage();
        if (hops >= 5 || !visited.add(uri.toString())) return Mono.error(failure("INVALID_VIDEO_INPUT"));
        return anonymous.get().uri(uri).exchangeToMono(response -> {
            int status = response.statusCode().value(); String location = response.headers().asHttpHeaders().getFirst("Location");
            // Consume with a hard buffer budget and release before following any redirect.
            return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(ignored -> {
                if (!Set.of(301, 302, 303, 307, 308).contains(status) || location == null) return Mono.error(failure("CONTENT_UNAVAILABLE"));
                URI next;
                try { next = uri.resolve(location); checked(next.toString()); } catch (RuntimeException e) { return Mono.error(failure("INVALID_VIDEO_INPUT")); }
                return expand(next, nextPage, hops + 1, visited);
            });
        });
    }
}
