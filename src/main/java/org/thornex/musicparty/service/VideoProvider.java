package org.thornex.musicparty.service;
import org.thornex.musicparty.dto.*;
import reactor.core.publisher.Mono;
public interface VideoProvider {
    String provider();
    boolean enabled();
    Mono<VideoDescriptor> metadata(Music music);
    Mono<ResolvedVideoSource> prepareSource(VideoDescriptor descriptor,boolean admitted);
}
