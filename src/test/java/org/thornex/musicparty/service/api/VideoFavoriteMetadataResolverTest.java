package org.thornex.musicparty.service.api;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.VideoDescriptor;
import org.thornex.musicparty.dto.VideoFavorites.Selected;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoFavoriteMetadataResolverTest {
    @Test void budgetPreservesCompletedItemsAndCancelsAllOtherWorkAtConcurrencyThree() {
        var api=mock(BilibiliVideoApiService.class);
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        when(api.getVideoDescriptor(anyString())).thenAnswer(invocation -> {
            String canonical=invocation.getArgument(0);
            if(canonical.endsWith(":p1")) return Mono.just(new VideoDescriptor(canonical,"BV1GJ411x7h7",1,1,"Title","Page","Owner",1000,""));
            return Mono.<VideoDescriptor>never().doOnSubscribe(subscription -> maximum.accumulateAndGet(active.incrementAndGet(),Math::max))
                    .doFinally(signal -> active.decrementAndGet());
        });
        List<Selected> selections=IntStream.rangeClosed(1,6).mapToObj(page -> new Selected(Integer.toString(page),"BV1GJ411x7h7",page)).toList();
        var results=new VideoFavoriteMetadataResolver(api).resolve(selections,Duration.ofMillis(50)).block(Duration.ofSeconds(2));
        assertNotNull(results);assertEquals(6,results.size());assertNotNull(results.getFirst().descriptor());
        assertEquals(5,results.stream().filter(item -> item.failure()!=null && item.failure().retryable()).count());
        assertEquals(3,maximum.get());assertEquals(0,active.get());
    }
    @Test void stableCredentialAndDurationReasonsNeverExposeUpstreamText() {
        var api=mock(BilibiliVideoApiService.class);
        when(api.getVideoDescriptor(anyString())).thenReturn(Mono.error(new BilibiliVideoApiService.VideoSourceException(BilibiliVideoApiService.FailureCode.CREDENTIALS_INVALID,"sensitive-upstream-body")));
        var result=new VideoFavoriteMetadataResolver(api).resolve(List.of(new Selected("1","BV1GJ411x7h7",2))).block().getFirst();
        assertEquals("CREDENTIALS_INVALID",result.failure().reasonCode());assertFalse(result.failure().retryable());
        assertFalse(result.toString().contains("sensitive-upstream-body"));
    }
}
