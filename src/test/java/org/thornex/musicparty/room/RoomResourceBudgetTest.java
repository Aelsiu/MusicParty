package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RoomResourceBudgetTest {
    @Test void cancelledDownloadReleasesCapacityAndWaitingRoomsGetTranscodersInOrder() throws Exception {
        var config=new MultiRoomProperties();config.setMaxConcurrentDownloads(1);config.setMaxConcurrentTranscoders(1);
        var budget=new RoomResourceBudget(config);
        var started=new CountDownLatch(1);
        var first=budget.download(()->{started.countDown();return Mono.never();}).subscribe();
        assertTrue(started.await(5,TimeUnit.SECONDS));first.dispose();
        assertEquals("next",budget.download(()->Mono.just("next")).block(Duration.ofSeconds(5)));
        var a=budget.transcoder("A");assertNotNull(a);
        assertNull(budget.transcoder("B"));assertNull(budget.transcoder("C"));
        a.close();a.close();assertNull(budget.transcoder("C"));
        var b=budget.transcoder("B");assertNotNull(b);b.close();
        var c=budget.transcoder("C");assertNotNull(c);c.close();
    }
}
