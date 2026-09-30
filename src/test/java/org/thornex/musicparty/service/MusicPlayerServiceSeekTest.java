package org.thornex.musicparty.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.PlayerAction;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlayerServiceSeekTest {
    private AppProperties props;
    private UserService users;
    private ApplicationEventPublisher publisher;
    private MusicPlayerService player;

    @BeforeEach void setup() {
        props=new AppProperties(); users=mock(UserService.class); publisher=mock(ApplicationEventPublisher.class);
        when(users.getUser("requester")).thenReturn(Optional.of(new User("token-a","requester","点歌者")));
        when(users.getUser("other")).thenReturn(Optional.of(new User("token-b","other","其他成员")));
        when(users.getUser("guest")).thenReturn(Optional.of(new User("guest-token","guest","游客")));
        player=build(); start("1"); clearInvocations(publisher);
    }
    private MusicPlayerService build() {
        return new MusicPlayerService(List.of(),users,mock(LocalCacheService.class),mock(LiveStreamService.class),
                mock(MusicQueueManager.class),publisher,props,mock(NeteaseMusicApiService.class),mock(PrivateDjService.class));
    }
    private void start(String id) {
        var music=new PlayableMusic(id,"Song",List.of("Artist"),120000,"netease","http://x/"+id,"",false);
        var item=new MusicQueueItem(UUID.randomUUID().toString(),new Music(id,"Song",List.of("Artist"),120000,"netease",""),
                new UserSummary("token-a","requester","点歌者",false),QueueItemStatus.READY);
        ReflectionTestUtils.invokeMethod(player,"applyNewSong",music,item);
    }
    private SeekRequest request(long position) { return new SeekRequest(player.getCurrentPlayerState().nowPlaying().playbackId(),position); }

    @Test void defaultPolicyRejectsEveryoneIncludingManagers() {
        assertEquals("DISABLED",player.getCurrentPlayerState().config().seekPolicy());
        assertFalse(player.seek(request(5000),"requester",false));
        assertFalse(player.seek(request(5000),"other",true));
        assertEquals(0,player.getCurrentPlayerState().nowPlaying().seekAvailableAt());
        verify(publisher,never()).publishEvent(any(PlayerStateEvent.class));
        verify(publisher,never()).publishEvent(any(SystemMessageEvent.class));
    }
    @Test void restrictedPolicyAllowsRequesterAndServerVerifiedManager() {
        props.getPlayer().setSeekPolicy("OWNER_AND_ENQUEUER");
        assertFalse(player.seek(request(5000),"other",false));
        assertTrue(player.seek(request(5000),"requester",false));
        ReflectionTestUtils.setField(player,"seekAvailableAt",0L);
        assertTrue(player.seek(request(6000),"other",true));
    }
    @Test void allPolicyStillRejectsGuestsAndUnknownSessions() {
        props.getPlayer().setSeekPolicy("ALL");
        assertFalse(player.seek(request(5000),"guest",true));
        assertFalse(player.seek(request(5000),"missing",true));
        assertTrue(player.seek(request(5000),"other",false));
    }
    @Test void cooldownIsSharedAndPublishesServerClockWithNewPosition() {
        props.getPlayer().setSeekPolicy("ALL");
        assertTrue(player.seek(request(15000),"requester",false));
        var state=player.getCurrentPlayerState();
        assertTrue(state.nowPlaying().currentPosition()>=15000 && state.nowPlaying().currentPosition()<15500);
        assertTrue(state.nowPlaying().seekAvailableAt()-state.nowPlaying().serverTime()>2500);
        assertFalse(player.seek(request(20000),"other",true));
        verify(publisher,times(1)).publishEvent(any(PlayerStateEvent.class));
        verify(publisher,times(1)).publishEvent(any(SystemMessageEvent.class));
        verify(publisher).publishEvent(argThat((org.springframework.context.ApplicationEvent value) ->
                value instanceof SystemMessageEvent event && event.getAction() == PlayerAction.JUMP
                        && "token-a".equals(event.getUserId()) && event.getLevel() == SystemMessageEvent.Level.INFO));
    }
    @Test void playingAndPausedStateArePreserved() {
        props.getPlayer().setSeekPolicy("ALL");
        assertTrue(player.seek(request(20000),"requester",false));
        assertFalse(player.getCurrentPlayerState().isPaused());
        ReflectionTestUtils.setField(player,"seekAvailableAt",0L); player.setPausedForTest(true);
        assertTrue(player.seek(request(30000),"other",false));
        assertTrue(player.getCurrentPlayerState().isPaused());
        assertEquals(30000,player.getCurrentPlayerState().nowPlaying().currentPosition());
    }
    @Test void staleRequestCannotSeekSameSongPlayedAgainAndNewTrackClearsCooldown() {
        props.getPlayer().setSeekPolicy("ALL"); var old=request(5000);
        assertTrue(player.seek(old,"requester",false)); start("2"); start("1");
        assertFalse(player.seek(old,"requester",false));
        assertTrue(player.seek(request(7000),"requester",false));
    }
    @Test void invalidPositionsAndLoadingRequestsDoNotConsumeCooldown() {
        props.getPlayer().setSeekPolicy("ALL");
        for(long p:List.of(-1L,120000L,Long.MAX_VALUE)) assertFalse(player.seek(request(p),"requester",true));
        assertFalse(player.seek(new SeekRequest(request(0).playbackId(),null),"requester",true));
        assertFalse(player.seek(null,"requester",true));
        ((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(player,"isLoading")).set(true);
        assertFalse(player.seek(request(1000),"requester",true));
        ((java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(player,"isLoading")).set(false);
        assertTrue(player.seek(request(0),"requester",true));
    }
    @Test void concurrentSeeksAcceptOnlyOne() throws Exception {
        props.getPlayer().setSeekPolicy("ALL"); var request=request(20000);var barrier=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{barrier.await();return player.seek(request,"requester",false);});
            var b=executor.submit(()->{barrier.await();return player.seek(request,"other",true);});barrier.countDown();
            assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
    }
    @Test void cooldownDoesNotCrossRoomPlayerInstances() {
        props.getPlayer().setSeekPolicy("ALL"); assertTrue(player.seek(request(10000),"requester",false));
        var first=player;player=build();start("1");assertTrue(player.seek(request(5000),"other",false));
        assertFalse(first.seek(new SeekRequest(first.getCurrentPlayerState().nowPlaying().playbackId(),8000L),"other",false));
    }
}
