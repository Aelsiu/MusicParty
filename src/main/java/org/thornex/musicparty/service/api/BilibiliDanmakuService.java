package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.thornex.musicparty.dto.VideoDanmakuSegment;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;

/** Public metadata requests only. No Cookie, media URL, disk cache or fallback endpoint. */
@Service
public final class BilibiliDanmakuService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BilibiliDanmakuService.class);
    private final WebClient client;
    public BilibiliDanmakuService() { this(anonymousClient()); }
    static WebClient anonymousClient() {
        // Use the same Windows/OS resolver as WebClientConfig. A separate bare WebClient
        // falls back to Netty public DNS on affected Windows hosts and cannot resolve Bilibili.
        var transport = reactor.netty.http.client.HttpClient.create().resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE);
        return WebClient.builder().clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(transport))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(BilibiliDanmakuDecoder.MAX_BODY)).build();
    }
    BilibiliDanmakuService(WebClient client) { this.client=client; }
    private record Key(String room,long cookieRevision,long cid,int segment,long duration) {}
    private record Cached(VideoDanmakuSegment value,int bytes,long at) {}
    private static final class Budget { int active; double tokens=2; long at=System.nanoTime(); }
    private final Map<String,Budget> identities=new HashMap<>();
    private final Map<String,Integer> rooms=new HashMap<>();
    private final Map<Key,Mono<VideoDanmakuSegment>> flights=new HashMap<>();
    private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private int upstream, subscribers, cachedBytes;
    private long cacheGeneration;
    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"DANMAKU_BUSY"); }
    public Mono<VideoDanmakuSegment> segment(String room,String identity,long revision,long cid,int segment,long duration) {
        return Mono.defer(() -> {
            String owner=room+":"+identity;
            synchronized(this) {
                if(identity==null || identity.isBlank() || cid<=0 || segment<1 || (long)(segment-1)*360000>=duration)
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"DANMAKU_INVALID_CONTEXT"));
                long now=System.nanoTime();
                identities.entrySet().removeIf(e -> e.getValue().active==0 && now-e.getValue().at>300_000_000_000L);
                if(!identities.containsKey(owner) && identities.size()>=1024) return Mono.error(busy());
                Budget budget=identities.computeIfAbsent(owner,k -> new Budget());
                budget.tokens=Math.min(2,budget.tokens+(now-budget.at)/5_000_000_000.0);budget.at=now;
                if(budget.tokens<1 || budget.active>=2 || rooms.getOrDefault(room,0)>=2 || subscribers>=256) return Mono.error(busy());
                budget.tokens--;budget.active++;subscribers++;rooms.merge(room,1,Integer::sum);
            }
            Key key=new Key(room,revision,cid,segment,duration);
            return load(key).doFinally(signal -> { synchronized(this) {
                Budget budget=identities.get(owner);if(budget!=null) budget.active--;
                subscribers--;rooms.computeIfPresent(room,(k,n)->n<=1?null:n-1);
            }});
        });
    }
    private synchronized Mono<VideoDanmakuSegment> load(Key key) {
        long generation=cacheGeneration;
        long now=System.nanoTime();
        var iterator=cache.entrySet().iterator();
        while(iterator.hasNext()) { var e=iterator.next();if(now-e.getValue().at()>300_000_000_000L
                || (e.getKey().room().equals(key.room()) && e.getKey().cookieRevision()!=key.cookieRevision())) {
            cachedBytes-=e.getValue().bytes();iterator.remove();
        }}
        Cached hit=cache.get(key);if(hit!=null) return Mono.just(hit.value());
        Mono<VideoDanmakuSegment> existing=flights.get(key);if(existing!=null) return existing;
        Mono<VideoDanmakuSegment> flight=Mono.defer(() -> {
            synchronized(this) { if(upstream>=8) return Mono.error(busy());upstream++; }
            return client.get().uri("https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid="+key.cid()+"&segment_index="+key.segment())
                .header("Accept-Encoding","identity").exchangeToMono(response -> {
                    log.debug("Danmaku upstream: status={}, declaredBytes={}, encoded={}", response.statusCode().value(),
                        response.headers().contentLength().orElse(-1), response.headers().header("Content-Encoding").stream().anyMatch(v -> !v.equalsIgnoreCase("identity")));
                    if(!response.statusCode().is2xxSuccessful() || response.headers().header("Content-Encoding").stream().anyMatch(v -> !v.equalsIgnoreCase("identity")))
                        return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"DANMAKU_UNAVAILABLE")));
                    if(response.headers().contentLength().orElse(0)>BilibiliDanmakuDecoder.MAX_BODY)
                        return response.releaseBody().then(Mono.error(new IllegalArgumentException("DANMAKU_OVER_BUDGET")));
                    return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]);
                }).timeout(Duration.ofSeconds(8)).map(body -> {
                    var decoded = BilibiliDanmakuDecoder.decodeWithStatistics(body,key.segment(),key.duration());
                    var statistics = decoded.statistics();
                    log.debug("Danmaku decode: bytes={}, parsed={}, displayable={}, filtered={}, duplicates={}", statistics.bodyBytes(),
                        statistics.parsedEntries(),statistics.displayableEntries(),statistics.filteredEntries(),statistics.duplicateEntries());
                    return decoded.segment();
                })
                .doOnError(error -> log.debug("Danmaku failure: kind={}", failureKind(error)))
                .doOnNext(value -> { synchronized(this) { if(generation==cacheGeneration) remember(key,value); } })
                .doFinally(signal -> { synchronized(this) { upstream--; }});
        }).doFinally(signal -> { synchronized(this) { flights.remove(key); } }).flux().replay(1).refCount(1).next();
        flights.put(key,flight);return flight;
    }
    private static String failureKind(Throwable error) {
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.net.UnknownHostException) return "DNS";
            if(cause instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
            if(cause instanceof org.springframework.core.io.buffer.DataBufferLimitException || "DANMAKU_OVER_BUDGET".equals(cause.getMessage())) return "LIMIT";
        }
        if(error instanceof IllegalArgumentException) return "DECODE";
        if(error instanceof ResponseStatusException) return "HTTP";
        return "UNKNOWN";
    }
    private synchronized void remember(Key key,VideoDanmakuSegment value) {
        int bytes;
        try { bytes=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value).length; }
        catch(Exception error) { throw new IllegalArgumentException("DANMAKU_INVALID"); }
        if(bytes>BilibiliDanmakuDecoder.MAX_BODY-2048) throw new IllegalArgumentException("DANMAKU_OVER_BUDGET");
        Cached previous=cache.put(key,new Cached(value,bytes,System.nanoTime()));
        cachedBytes+=bytes-(previous==null?0:previous.bytes());
        while(cache.size()>64 || cachedBytes>16*1024*1024) { var first=cache.entrySet().iterator();var e=first.next();cachedBytes-=e.getValue().bytes();first.remove(); }
    }
    public synchronized void clearRoom(String room) {
        cacheGeneration++;
        var iterator=cache.entrySet().iterator();while(iterator.hasNext()) { var e=iterator.next();if(e.getKey().room().equals(room)) { cachedBytes-=e.getValue().bytes();iterator.remove(); } }
    }
}
