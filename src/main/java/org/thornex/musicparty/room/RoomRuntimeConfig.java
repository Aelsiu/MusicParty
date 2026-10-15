package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import org.reactivestreams.Subscription;
import reactor.util.context.Context;

@Configuration
public class RoomRuntimeConfig {
    @Bean public static RoomScope roomScope() { return new RoomScope(); }
    @Bean public static CustomScopeConfigurer roomScopeConfigurer(RoomScope scope) {
        var config=new CustomScopeConfigurer(); config.addScope("room",scope); return config;
    }
    @Bean("defaultAppProperties") @ConfigurationProperties("app.music-api")
    public AppProperties defaults() { return new AppProperties(); }
    @Bean @Primary @RoomScoped
    public AppProperties roomProperties(@Qualifier("defaultAppProperties") AppProperties defaults,RoomRepository repository,ObjectMapper mapper,SystemConfigService systemConfig) throws Exception {
        String saved=repository.payload(RoomContext.require(),"config");
        AppProperties config=saved==null?mapper.readValue(mapper.writeValueAsString(defaults),AppProperties.class):mapper.readValue(saved,AppProperties.class);
        // Server endpoints are deployment settings, credentials belong to the room.
        config.setBaseUrl(defaults.getBaseUrl()); config.setFfmpegPath(defaults.getFfmpegPath());
        config.getNetease().setBaseUrl(defaults.getNetease().getBaseUrl());
        config.getBilibili().setBaseUrl(defaults.getBilibili().getBaseUrl());
        config.setAdminPassword(null);
        if(saved==null) { config.getNetease().setCookie("");config.getBilibili().setCookie("");config.setPrivateDj(new AppProperties.PrivateDjConfig()); }
        systemConfig.bind(config);
        return config;
    }
    @Bean public Object roomReactorContext(RoomScope scope) {
        // Capture at assembly time as MVC can subscribe after the servlet filter has returned.
        Hooks.onEachOperator("music-party-room",publisher -> {
            String assembled=RoomContext.current();
            if (assembled == null) return publisher;
            return Operators.<Object,Object>lift((scannable,actual) -> new CoreSubscriber<Object>() {
                private final String id=assembled!=null?assembled:actual.currentContext().getOrDefault("musicPartyRoom",null);
                private Subscription upstream;
                @Override public Context currentContext() { return id==null?actual.currentContext():actual.currentContext().put("musicPartyRoom",id); }
                private void run(Runnable task) { if(id==null || scope.active(id)) try(var ignored=RoomContext.enter(id)) { task.run(); } }
                @Override public void onSubscribe(Subscription s) {
                    upstream=s;
                    if(id!=null && !scope.active(id)) { s.cancel(); return; }
                    class ContextSubscription extends java.util.AbstractQueue<Object> implements reactor.core.Fuseable.QueueSubscription<Object> {
                        @Override public void request(long n) { run(() -> s.request(n)); }
                        @Override public void cancel() { try(var ignored=RoomContext.enter(id)) { s.cancel(); } }
                        // Disable fusion at the context boundary so each value has an explicit callback.
                        @Override public int requestFusion(int mode) { return reactor.core.Fuseable.NONE; }
                        @Override public Object poll() { return null; }
                        @Override public Object peek() { return null; }
                        @Override public boolean offer(Object value) { throw new UnsupportedOperationException(); }
                        @Override public int size() { return 0; }
                        @Override public java.util.Iterator<Object> iterator() { return java.util.Collections.emptyIterator(); }
                    }
                    run(() -> actual.onSubscribe(new ContextSubscription()));
                }
                private boolean cancelledRoom() {
                    if(id==null || scope.active(id)) return false;
                    try(var ignored=RoomContext.enter(id)) { if(upstream!=null) upstream.cancel(); }
                    return true;
                }
                @Override public void onNext(Object value) {
                    if(cancelledRoom()) {
                        if(value instanceof org.springframework.core.io.buffer.DataBuffer buffer) org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                        else Operators.onDiscard(value,currentContext());
                    } else run(() -> actual.onNext(value));
                }
                @Override public void onError(Throwable error) { if(!cancelledRoom()) run(() -> actual.onError(error)); }
                @Override public void onComplete() { if(!cancelledRoom()) run(actual::onComplete); }
            }).apply(publisher);
        });
        Schedulers.onScheduleHook("music-party-room",RoomContext::capture);
        return new Object();
    }
}
