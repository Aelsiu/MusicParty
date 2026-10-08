package org.thornex.musicparty.service;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VideoQueueP2Test {
    MusicQueueItem add(VideoQueueManager q,String id,String token) {
        return q.add(new Music(id,"Video",List.of("UP"),60000,"bilibili-video",null),new UserSummary(token,token,"Member",false),QueueItemStatus.READY);
    }
    MusicQueueItem next(VideoQueueManager q,Set<String> online) {
        Map<String,QueueItemStatus> states=new HashMap<>(); q.getQueueSnapshot().forEach(x -> states.put(x.music().id(),QueueItemStatus.READY));
        return q.pollNext(PlayMode.SHUFFLE,true,false,states,online);
    }
    @Test void globalTopPrecedesFairTurnWithoutAdvancingMemberCursor() {
        var q=new VideoQueueManager(new AppProperties()); var a=add(q,"BV1GJ411x7h7:p1","A"); add(q,"BV1GJ411x7h7:p2","A"); add(q,"BV1Q541167Qg:p1","B");
        assertEquals(TopResult.PERSONAL,q.top(a.queueId(),PlayMode.SHUFFLE)); assertEquals(TopResult.GLOBAL,q.top(a.queueId(),PlayMode.SHUFFLE)); assertEquals(TopResult.NONE,q.top(a.queueId(),PlayMode.SHUFFLE));
        assertEquals(a.queueId(),next(q,Set.of("A","B")).queueId());
        assertEquals("A",next(q,Set.of("A","B")).enqueuedBy().token());
        assertEquals("B",next(q,Set.of("A","B")).enqueuedBy().token());
    }
    @Test void onlinePriorityFallsBackWhenNobodyIsOnlineAndDifferentPagesStayDistinct() {
        var q=new VideoQueueManager(new AppProperties()); add(q,"BV1GJ411x7h7:p1","A"); add(q,"BV1GJ411x7h7:p2","A"); var b=add(q,"BV1Q541167Qg:p1","B");
        assertEquals(3,q.getQueueSnapshot().size()); assertEquals(b.queueId(),next(q,Set.of("B")).queueId());
        assertEquals("A",next(q,Set.of()).enqueuedBy().token()); assertEquals("A",next(q,Set.of()).enqueuedBy().token());
    }
    @Test void videoDeletionUsesOwnerAuthorizationAndHistoryFallbackRemainsMetadataOnly() {
        var p=new AppProperties(); var q=new VideoQueueManager(p); var item=add(q,"BV1GJ411x7h7:p1","A");
        assertTrue(q.removeAuthorized(item.queueId(),"B",false).isEmpty()); assertTrue(q.removeAuthorized(item.queueId(),"B",true).isPresent());
        q.addToHistory(item.music()); var history=next(q,Set.of()); assertEquals(item.music(),history.music()); assertTrue(q.getQueueSnapshot().isEmpty());
        p.getQueue().setHistorySize(0); q.trimHistoryToLimit(); assertNull(next(q,Set.of()));
    }
}
