package org.thornex.musicparty.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.ExchangeFunctions;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.room.RoomContext;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // 配置 ExchangeStrategies 来增加缓冲区大小
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 设置为 10MB
                .build();

        // 关键：显式使用 JDK 系统解析器（DefaultAddressResolverGroup.INSTANCE），而非 reactor-netty 默认的异步 DNS 解析器。
        // Netty 的 DnsNameResolver 在 Windows 上经常读不到系统 DNS（platformDefault() 失败），会回退到 Google Public DNS
        // （8.8.8.8/8.8.4.4）——国内网络不可达 → api.bilibili.com 解析失败 → UnknownHostException → 整个 B站源 500。
        // JDK 解析器走 OS 的 getaddrinfo，跟随系统/适配器 DNS，与浏览器解析结果一致。
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE);

        var exchange = ExchangeFunctions.create(new ReactorClientHttpConnector(httpClient), strategies);
        return WebClient.builder()
                // Shared transport and keep-alive cleanup must not inherit a room's lifetime.
                // Application operators still carry their room context and cancel deleted-room responses.
                .exchangeFunction(request -> new Mono<ClientResponse>() {
                    @Override public void subscribe(CoreSubscriber<? super ClientResponse> subscriber) {
                        try (var ignored = RoomContext.enter(null)) {
                            exchange.exchange(request).subscribe(new CoreSubscriber<ClientResponse>() {
                                @Override public Context currentContext() { return subscriber.currentContext(); }
                                @Override public void onSubscribe(Subscription upstream) {
                                    subscriber.onSubscribe(new Subscription() {
                                        @Override public void request(long n) {
                                            try (var ignored = RoomContext.enter(null)) { upstream.request(n); }
                                        }
                                        @Override public void cancel() {
                                            try (var ignored = RoomContext.enter(null)) { upstream.cancel(); }
                                        }
                                    });
                                }
                                @Override public void onNext(ClientResponse response) { subscriber.onNext(response); }
                                @Override public void onError(Throwable error) { subscriber.onError(error); }
                                @Override public void onComplete() { subscriber.onComplete(); }
                            });
                        }
                    }
                })
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build();
    }
}
