package org.thornex.musicparty.service.api;

import org.springframework.stereotype.Service;
import org.thornex.musicparty.dto.VideoDescriptor;
import org.thornex.musicparty.dto.VideoFavorites.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded metadata-only work. Returning results never mutates actor-owned state. */
@Service @org.thornex.musicparty.room.RoomScoped
public class VideoFavoriteMetadataResolver {
    private final BilibiliVideoApiService videos;
    public VideoFavoriteMetadataResolver(BilibiliVideoApiService videos) { this.videos=videos; }
    public record Resolved(Selected selection, VideoDescriptor descriptor, ItemResult failure) {}
    public Mono<List<Resolved>> resolve(List<Selected> selections) { return resolve(selections, Duration.ofSeconds(45)); }
    public Mono<List<Resolved>> resolve(List<Selected> selections, Duration budget) {
        List<Selected> immutable=List.copyOf(selections);
        return Mono.defer(() -> {
            Map<String,Resolved> completed=new ConcurrentHashMap<>();
            Mono<Long> work=Flux.fromIterable(immutable).flatMap(selection ->
                    videos.getVideoDescriptor(selection.canonicalId()).timeout(Duration.ofSeconds(20))
                            .map(descriptor -> new Resolved(selection,descriptor,null))
                            .onErrorResume(error -> Mono.just(new Resolved(selection,null,mapFailure(selection,error))))
                            .doOnNext(result -> completed.put(result.selection.itemId(),result)),3).count();
            return Mono.firstWithSignal(work,Mono.delay(budget)).map(ignored -> immutable.stream().map(selection ->
                    completed.getOrDefault(selection.itemId(),new Resolved(selection,null,
                            new ItemResult(selection.itemId(),null,"TEMPORARY_FAILURE","TEMPORARY_FAILURE",true,null)))).toList());
        });
    }
    private static ItemResult mapFailure(Selected selection, Throwable error) {
        String code="TEMPORARY_FAILURE";
        if(error instanceof org.thornex.musicparty.dto.VideoFavorites.Failure failure) code=failure.getCode();
        if(error instanceof BilibiliVideoApiService.VideoSourceException source) code=switch(source.getCode()) {
            case VIDEO_TOO_LONG -> "DURATION_EXCEEDED";
            case CREDENTIALS_MISSING -> "CREDENTIALS_REQUIRED";
            case CREDENTIALS_INVALID -> "CREDENTIALS_INVALID";
            case UNSUPPORTED_INPUT -> "INVALID_SELECTION";
            case CONTENT_UNAVAILABLE, UNSUPPORTED_SOURCE, INCOMPLETE_SOURCE -> "CONTENT_UNAVAILABLE";
            default -> "TEMPORARY_FAILURE";
        };
        return new ItemResult(selection.itemId(),null,code,code,code.equals("TEMPORARY_FAILURE"),null);
    }
}
