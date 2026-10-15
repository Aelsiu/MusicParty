package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import static org.thornex.musicparty.dto.VideoFavorites.failure;

/** Process singleflight for the site's publicly shipped anonymous client configuration. */
@Service
public class XifanPublicConfigResolver {
    private final WebClient client=WebClient.builder().defaultHeader("User-Agent","Mozilla/5.0 MirthParty/Video").codecs(c->c.defaultCodecs().maxInMemorySize(1024*1024)).build();
    @Value("${app.xifan.public-client-key:}") private String configured="";
    private String ready;
    private Mono<String> flight;private long expiry,cooldown;private long revision,discoveryAttempt;
    public synchronized long revision(){return revision;}
    public synchronized void invalidate(){flight=null;ready=null;expiry=0;cooldown=0;revision++;discoveryAttempt++;}
    public synchronized Mono<String> playbackKey(){
        if(!configured.isBlank())return Mono.just(configured);
        if(ready!=null && System.currentTimeMillis()<expiry)return Mono.just(ready);
        key().subscribe(value->{},error->{});return Mono.error(failure("XIFAN_UNAVAILABLE"));
    }
    public synchronized Mono<String> key(){
        if(!configured.isBlank())return Mono.just(configured);
        long now=System.currentTimeMillis();if(ready!=null && now<expiry)return Mono.just(ready);
        if(flight!=null && now<expiry)return flight;
        if(now<cooldown)return Mono.error(failure("XIFAN_UNAVAILABLE"));
        long attempt=++discoveryAttempt;
        expiry=now+6*3600_000L;
        // Public configuration belongs to the process. Room Reactor hooks must not
        // bind its cached publisher or network work to the first caller's lifetime.
        try(var ignored=org.thornex.musicparty.room.RoomContext.enter(null)){
        flight=client.get().uri("https://next.xifanacg.com/").retrieve().bodyToMono(String.class).retryWhen(reactor.util.retry.Retry.fixedDelay(1,Duration.ofMillis(300))).flatMap(html->{
            var matcher=Pattern.compile("<script[^>]+src=\"([^\"]+)\"").matcher(html);Set<String> scripts=new LinkedHashSet<>();
            while(matcher.find()){var uri=java.net.URI.create("https://next.xifanacg.com/").resolve(matcher.group(1));
                if("https".equals(uri.getScheme()) && "next.xifanacg.com".equals(uri.getHost()) && uri.getPort()==-1 && uri.getUserInfo()==null && uri.getPath().startsWith("/_next/static/"))scripts.add(uri.toString());
                if(scripts.size()>32)throw failure("XIFAN_UNAVAILABLE");}
            var bytes=new java.util.concurrent.atomic.AtomicInteger();
            return Flux.fromIterable(scripts).flatMap(url->client.get().uri(url).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(5)).onErrorResume(error->Mono.empty()).map(script->{
                if(bytes.addAndGet(script.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)>8*1024*1024)throw failure("XIFAN_UNAVAILABLE");
                var key=Pattern.compile("sb_publishable_[A-Za-z0-9_-]{16,256}").matcher(script);return key.find()?key.group():"";
            }),2).filter(value->!value.isBlank()).next().switchIfEmpty(Mono.error(failure("XIFAN_UNAVAILABLE")));
        }).timeout(Duration.ofSeconds(20)).doOnNext(value->{synchronized(this){if(attempt==discoveryAttempt)ready=value;}}).doOnError(error->{synchronized(this){if(attempt==discoveryAttempt){cooldown=System.currentTimeMillis()+300_000;expiry=0;}}})
          .onErrorMap(error->failure("XIFAN_UNAVAILABLE")).cache();
        // Start the single shared discovery outside the caller's subscription context.
        // Cancelling or deleting that room cannot strand other rooms' reads.
        flight.subscribe(value->{},error->{});
        return flight;
        }
    }
}
