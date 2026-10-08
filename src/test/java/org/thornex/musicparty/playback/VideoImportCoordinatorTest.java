package org.thornex.musicparty.playback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.room.RoomAccessService;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.api.*;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoImportCoordinatorTest {
    private final PlaybackMailbox mailbox=new PlaybackMailbox("room",32,Runnable::run);
    private final PlaybackEventSink sink=new PlaybackEventSink();
    private final MusicPlayerService music=mock(MusicPlayerService.class);
    private final VideoPlayerService video=mock(VideoPlayerService.class);
    private final QueuePersistenceService persistence=mock(QueuePersistenceService.class);
    private final UserService users=mock(UserService.class);
    private final RoomAccessService access=mock(RoomAccessService.class);
    private final SimpMessagingTemplate messaging=mock(SimpMessagingTemplate.class);
    private final BilibiliVideoFavoritesService favorites=mock(BilibiliVideoFavoritesService.class);
    private final VideoFavoriteMetadataResolver resolver=mock(VideoFavoriteMetadataResolver.class);
    private final ObjectMapper json=new ObjectMapper();
    private RoomPlaybackCoordinator coordinator;
    private final String bv="BV1GJ411x7h7";
    private final VideoDescriptor descriptor=new VideoDescriptor(bv+":p2",bv,2,2,"title","part","owner",12000,"");
    @BeforeEach void setup() throws Exception {
        when(persistence.restoredMode()).thenReturn("VIDEO");
        PlayerState raw=new PlayerState(null,List.of(),"SEQUENTIAL",false,true,false,List.of(),true,false,false,false,false,0,false,false,.5,0,0,0,null);
        when(video.getCurrentPlayerState()).thenReturn(raw);when(music.getCurrentPlayerState()).thenReturn(raw);
        when(video.getSourceStatus()).thenReturn("EMPTY");when(video.isVideoSourceEnabled()).thenReturn(true);
        when(video.getImportBatchLimit()).thenReturn(100);when(video.availableImportSlots("member")).thenReturn(10);
        when(video.reserveImportSlots("member",1)).thenReturn("reservation");
        when(video.commitImportedDescriptor(any(),any())).thenReturn("queue-id");
        for(String session:List.of("origin","reconnect","other-tab")) {
            when(users.getUser(session)).thenReturn(Optional.of(new User("member",session,"Alice")));
            when(access.connection(session)).thenReturn(new RoomAccessService.Connection("room","manager","admission","browser"));
        }
        when(favorites.page("123",1)).thenReturn(Mono.just(new VideoFavorites.Page("123","folder",1,List.of(new VideoFavorites.Item("1",bv,"title","","owner",12000,true,null)),false)));
        coordinator=new RoomPlaybackCoordinator(mailbox,sink,music,video,persistence,users,access,mock(LiveStreamService.class),messaging,mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(coordinator,"favorites",favorites);ReflectionTestUtils.setField(coordinator,"favoriteResolver",resolver);
        coordinator.read();clearInvocations(video,messaging);
    }
    private ObjectNode context(String request) {
        var state=coordinator.read();return json.createObjectNode().put("requestId",request).put("expectedPlaybackEpoch",state.playbackEpoch()).put("expectedMode",state.mediaMode()).put("expectedModeEpoch",state.modeEpoch());
    }
    private ObjectNode request() {
        var context=context("import");var preview=coordinator.favoritePreview("browser",context,"https://space.bilibili.com/1/favlist?fid=123",null,1).block();
        context.put("previewId",(String)preview.get("previewId"));context.putArray("items").addObject().put("itemId","1").put("page",2);return context;
    }
    private List<VideoFavoriteMetadataResolver.Resolved> resolved() { return List.of(new VideoFavoriteMetadataResolver.Resolved(new VideoFavorites.Selected("1",bv,2),descriptor,null)); }
    private Map<?,?> lastMessage(String kind) {
        return mockingDetails(messaging).getInvocations().stream().filter(invocation -> invocation.getMethod().getName().equals("convertAndSendToUser")
                && invocation.getArguments().length>=3 && invocation.getArguments()[2] instanceof Map<?,?> map && kind.equals(map.get("kind")))
                .reduce((first,last)->last).map(invocation -> (Map<?,?>)invocation.getArguments()[2]).orElseThrow();
    }
    @Test void lostAckReconnectSameKeyReturnsOriginalTerminalWithoutAnotherCommit() {
        when(resolver.resolve(anyList())).thenReturn(Mono.just(resolved()));
        var body=request();coordinator.favoriteImport("origin",body).join();
        var terminal=lastMessage("IMPORT_RESULT");assertEquals("COMPLETED",terminal.get("status"));
        coordinator.favoriteImport("reconnect",body.deepCopy()).join();
        assertEquals(terminal.get("operationId"),lastMessage("IMPORT_STATUS").get("operationId"));
        verify(video,times(1)).commitImportedDescriptor(any(),any());verify(video).releaseImportSlots("reservation");assertEquals(0,mailbox.reservedCompletionCount());
        coordinator.favoriteImport("reconnect",body.deepCopy().put("previewId","different")).join();
        assertEquals("REQUEST_ID_CONFLICT",lastMessage("IMPORT_ERROR").get("code"));
    }
    @Test void onlyOriginSessionDisconnectCancelsAndLateMetadataCannotRevive() {
        Sinks.One<List<VideoFavoriteMetadataResolver.Resolved>> delayed=Sinks.one();when(resolver.resolve(anyList())).thenReturn(delayed.asMono());
        var body=request();coordinator.favoriteImport("origin",body).join();assertEquals(1,mailbox.reservedCompletionCount());
        coordinator.internal(()->coordinator.sessionDisconnected("other-tab")).join();assertEquals(1,mailbox.reservedCompletionCount());
        coordinator.internal(()->coordinator.sessionDisconnected("origin")).join();assertEquals("CANCELLED",lastMessage("IMPORT_RESULT").get("status"));
        delayed.tryEmitValue(resolved());verify(video,never()).commitImportedDescriptor(any(),any());verify(video).releaseImportSlots("reservation");assertEquals(0,mailbox.reservedCompletionCount());
        coordinator.importStatus("reconnect",context("query").put("originalRequestId","import")).join();assertEquals("CANCELLED",lastMessage("IMPORT_STATUS").get("status"));
    }
    @Test void modeSwitchCancelsNewBatchButOrdinarySingleEnqueueRetainsEnginePath() {
        Sinks.One<List<VideoFavoriteMetadataResolver.Resolved>> delayed=Sinks.one();when(resolver.resolve(anyList())).thenReturn(delayed.asMono());
        coordinator.favoriteImport("origin",request()).join();
        var single=context("single").put("platform","bilibili-video").put("musicId",bv+":p2");
        coordinator.command("origin","enqueue",single).join();verify(video).enqueue(any(),eq("origin"));
        coordinator.command("origin","mode",context("mode").put("mode","MUSIC")).join();assertEquals("CANCELLED",lastMessage("IMPORT_RESULT").get("status"));
        delayed.tryEmitValue(resolved());verify(video,never()).commitImportedDescriptor(any(),any());assertEquals(0,mailbox.reservedCompletionCount());
        coordinator.importStatus("reconnect",context("query").put("originalRequestId","import")).join();assertEquals("VIDEO",lastMessage("IMPORT_STATUS").get("mediaMode"));
    }
    @Test void ticketAndLedgerRollbackOnReservationFailure() {
        when(video.reserveImportSlots("member",1)).thenThrow(new IllegalArgumentException("QUOTA_EXCEEDED"));
        var body=request();coordinator.favoriteImport("origin",body).join();assertEquals(0,mailbox.reservedCompletionCount());verify(resolver,never()).resolve(anyList());
        doReturn("reservation").when(video).reserveImportSlots("member",1);when(resolver.resolve(anyList())).thenReturn(Mono.just(resolved()));
        coordinator.favoriteImport("origin",body).join();assertEquals("COMPLETED",lastMessage("IMPORT_RESULT").get("status"));
    }
    @Test void historyReturnsSelectionMetadataAndRevisionMismatchIsExplicit() {
        when(video.getHistoryRevision()).thenReturn(7L);
        when(video.getHistorySnapshot()).thenReturn(List.of(new Music(bv+":p2","Video",List.of("Owner"),12000,"bilibili-video","https://i0.hdslb.com/cover.jpg")));
        var response=coordinator.history(context("history"),1,20,null);
        Map<?,?> item=(Map<?,?>)((List<?>)response.get("items")).getFirst();
        assertEquals(bv,item.get("bvid"));assertEquals(2,item.get("page"));assertEquals(bv+":p2",item.get("canonicalId"));
        assertEquals("https://i0.hdslb.com/cover.jpg",item.get("coverUrl"));assertFalse(item.containsKey("url"));
        assertEquals(7L,response.get("historyRevision"));assertEquals(false,response.get("hasMore"));
        var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->coordinator.history(context("changed"),1,20,6L));
        assertEquals("HISTORY_CHANGED",error.getReason());
    }
}
