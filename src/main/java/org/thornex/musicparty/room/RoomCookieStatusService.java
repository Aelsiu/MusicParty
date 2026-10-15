package org.thornex.musicparty.room;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.playback.RoomPlaybackCoordinator;
import org.thornex.musicparty.service.api.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Room-local bounded checks. HTTP waits outside the playback mailbox; responses contain enums only. */
@Service @RoomScoped
public class RoomCookieStatusService {
    private final NeteaseMusicApiService netease;
    private final BilibiliCookieService bilibili;
    private final RoomPlaybackCoordinator playback;
    private final LongSupplier now;
    private final Map<String,Entry> cache=new HashMap<>();
    private boolean closed;
    private static final long CACHE_MS=30000;
    private static final class Entry {
        final long revision;
        long expiresAt;
        CookieAuthentication.Result result=CookieAuthentication.Result.UNKNOWN;
        Mono<CookieAuthentication.Result> pending;
        Entry(long revision) {this.revision=revision;}
    }
    private record Credentials(CookieAuthentication.Credential netease, CookieAuthentication.Credential bilibili) {}
    @org.springframework.beans.factory.annotation.Autowired
    public RoomCookieStatusService(NeteaseMusicApiService netease,BilibiliCookieService bilibili,RoomPlaybackCoordinator playback) {
        this(netease,bilibili,playback,System::currentTimeMillis);
    }
    RoomCookieStatusService(NeteaseMusicApiService netease,BilibiliCookieService bilibili,RoomPlaybackCoordinator playback,LongSupplier now) {
        this.netease=netease;this.bilibili=bilibili;this.playback=playback;this.now=now;
    }
    private Credentials snapshot() {return new Credentials(netease.credentialSnapshot(),bilibili.credentialSnapshot());}
    public Map<String,String> status(String manager) {
        RoomContext.require();
        Credentials original=playback.manage(manager,this::snapshot);
        var result=Mono.zip(check("netease",original.netease(),netease::checkCookieAuthentication),
                check("bilibili",original.bilibili(),bilibili::checkCookieAuthentication)).block(Duration.ofSeconds(4));
        // Admission and Cookie generations are rechecked in the actor after all external work.
        return playback.manage(manager,()->{
            Credentials current=snapshot();
            return Map.of("netease",display(original.netease(),current.netease(),result.getT1()),
                    "bilibili",display(original.bilibili(),current.bilibili(),result.getT2()));
        });
    }
    private static String display(CookieAuthentication.Credential original,CookieAuthentication.Credential current,CookieAuthentication.Result result) {
        if(!current.configured()) return "UNCONFIGURED";
        return original.revision()==current.revision() && result==CookieAuthentication.Result.INVALID?"EXPIRED":"CONFIGURED";
    }
    private synchronized Mono<CookieAuthentication.Result> check(String platform,CookieAuthentication.Credential credential,Function<String,Mono<CookieAuthentication.Result>> checker) {
        if(!credential.configured() || closed) return Mono.just(CookieAuthentication.Result.UNKNOWN);
        Entry entry=cache.get(platform);
        // A delayed older snapshot must not replace a newer generation's cache or singleflight.
        if(entry!=null && entry.revision>credential.revision()) return Mono.just(CookieAuthentication.Result.UNKNOWN);
        if(entry==null || entry.revision!=credential.revision()) {entry=new Entry(credential.revision());cache.put(platform,entry);}
        if(entry.pending!=null) return entry.pending;
        if(now.getAsLong()<entry.expiresAt) return Mono.just(entry.result);
        Entry current=entry;
        current.pending=Mono.defer(()->checker.apply(credential.value()))
                .timeout(Duration.ofSeconds(3)).defaultIfEmpty(CookieAuthentication.Result.UNKNOWN)
                .onErrorReturn(CookieAuthentication.Result.UNKNOWN)
                .map(value->{synchronized(this){
                    if(!closed && cache.get(platform)==current) {
                        if(value!=CookieAuthentication.Result.UNKNOWN) current.result=value;
                        current.expiresAt=now.getAsLong()+CACHE_MS;
                    }
                    return value==CookieAuthentication.Result.UNKNOWN?current.result:value;
                }})
                .doFinally(signal->{synchronized(this){current.pending=null;}}).cache();
        return current.pending;
    }
    @PreDestroy public synchronized void destroy() {closed=true;cache.clear();}
}
