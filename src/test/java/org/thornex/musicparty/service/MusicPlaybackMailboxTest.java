package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.MediaMode;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.playback.*;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicPlaybackMailboxTest {
    private static class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> workers = new ArrayDeque<>();
        @Override public void execute(Runnable work) { workers.add(work); }
        void drain() { while (!workers.isEmpty()) workers.removeFirst().run(); }
    }
    private final ManualExecutor executor = new ManualExecutor();
    private final PlaybackMailbox mailbox = new PlaybackMailbox("room", 32, executor);
    private final PlaybackEventSink sink = new PlaybackEventSink();
    private final AppProperties props = new AppProperties();
    private final MusicQueueManager queue = new MusicQueueManager(props);
    private final UserSummary requester = new UserSummary("u", "s", "用户", false);
    private final IMusicApiService api = mock(IMusicApiService.class);
    private final NeteaseMusicApiService netease = mock(NeteaseMusicApiService.class);
    private final UserService users = mock(UserService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private MusicPlayerService player;
    private PrivateDjService dj;

    @BeforeEach void setup() {
        when(api.getPlatformName()).thenReturn("netease");
        when(users.getUser("s")).thenReturn(Optional.of(new User("u", "s", "用户")));
        when(users.getRecentlyActiveUserTokens()).thenReturn(Set.of("u"));
        when(users.getOnlineUserSummaries()).thenReturn(List.of(requester));
        dj = new PrivateDjService(netease, props);
        dj.configurePlaybackMailbox(mailbox);
        player = new MusicPlayerService(List.of(api), users, mock(LocalCacheService.class), mock(LiveStreamService.class),
                queue, publisher, props, netease, dj);
        player.configurePlayback(mailbox, sink, new PlaybackControlLimiter());
        player.setPausedForTest(true);
    }

    private MusicQueueItem enqueueReady(String id) {
        return queue.add(new Music(id, "歌曲" + id, List.of("作者"), 120_000, "netease", ""), requester, QueueItemStatus.READY);
    }
    private PlayableMusic playable(String id) {
        return new PlayableMusic(id, "歌曲" + id, List.of("作者"), 120_000, "netease", "https://cdn.test/" + id, "", false);
    }
    private void command(Runnable action) { mailbox.submit(action); executor.drain(); }

    @Test void freezingLoadingSongRetainsItsIdentityQuotaAndRetriesBeforePollingAgain() {
        props.getQueue().setMaxSize(1);
        var item = enqueueReady("1");
        Sinks.One<PlayableMusic> first = Sinks.one(), second = Sinks.one();
        when(api.getPlayableMusic("1")).thenReturn(first.asMono(), second.asMono());
        command(() -> player.togglePause("s"));
        assertTrue(player.getCurrentPlayerState().isLoading());
        assertEquals(item.queueId(), queue.getQueueSnapshot().getFirst().queueId());
        assertEquals(1, player.getPersistenceQueueSnapshot().size());
        assertNull(enqueueReady("2"), "pending still occupies the queue slot");
        command(player::freezeForModeSwitch);
        first.tryEmitValue(playable("1"));
        executor.drain();
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertTrue(player.getCurrentPlayerState().isPaused());
        command(player::activatePaused);
        command(() -> player.togglePause("s"));
        second.tryEmitValue(playable("1"));
        executor.drain();
        assertEquals("1", player.getCurrentPlayerState().nowPlaying().music().id());
        assertTrue(player.getPersistenceQueueSnapshot().isEmpty());
        verify(api, times(2)).getPlayableMusic("1");
        verify(publisher, never()).publishEvent(any());
    }

    @Test void clearCancelsAcceptedMetadataOperationSoItsLateResultCannotRepopulateQueue() {
        Sinks.One<PlayableMusic> metadata = Sinks.one();
        when(api.getPlayableMusic("1")).thenReturn(metadata.asMono());
        command(() -> player.enqueue(new EnqueueRequest("netease", "1"), "s"));
        assertEquals(1, mailbox.reservedCompletionCount());
        command(() -> player.clearUserQueue("u"));
        assertEquals(0, mailbox.reservedCompletionCount());
        metadata.tryEmitValue(playable("1"));
        executor.drain();
        assertTrue(queue.getQueueSnapshot().isEmpty());
        assertEquals(1, queue.reserve("u", 1).count(), "quota was returned exactly once");
    }

    @Test void acceptedMetadataStaysInOriginalMusicQueueAfterModeSwitch() {
        Sinks.One<PlayableMusic> metadata = Sinks.one();
        when(api.getPlayableMusic("1")).thenReturn(metadata.asMono());
        command(() -> player.enqueue(new EnqueueRequest("netease", "1"), "s"));
        command(player::freezeForModeSwitch);
        sink.take(MediaMode.VIDEO);
        metadata.tryEmitValue(playable("1"));
        executor.drain();
        assertEquals("1", queue.getQueueSnapshot().getFirst().music().id());
        assertFalse(sink.take(MediaMode.VIDEO).stateDirty());
        assertTrue(sink.take(MediaMode.MUSIC).queueDirty());
        assertNull(player.getCurrentPlayerState().nowPlaying());
    }

    @Test void providerSegmentCommittedAheadOfFreezeIsRetainedForManualResume() throws Exception {
        props.getPrivateDj().setMode("FM");
        props.getPrivateDj().setCustodyEnabled(true);
        Sinks.One<com.fasterxml.jackson.databind.JsonNode> batch = Sinks.one();
        when(netease.fetchPersonalFm()).thenReturn(batch.asMono());
        when(netease.getFmDjSongUrl("77")).thenReturn(Mono.just(new NeteaseMusicApiService.SongUrl("https://cdn.test/77", "standard")));
        command(() -> player.togglePause("s"));
        batch.tryEmitValue(new ObjectMapper().readTree("{\"data\":[{\"id\":77,\"name\":\"FM曲目\",\"duration\":100000,\"artists\":[],\"album\":{}}]}"));
        // Provider commit is queued first; its engine commit will be appended behind this freeze.
        mailbox.submit(player::freezeForModeSwitch);
        executor.drain();
        assertNull(player.getCurrentPlayerState().nowPlaying());
        verify(netease, never()).getFmDjSongUrl(anyString());
        command(player::activatePaused);
        command(() -> player.togglePause("s"));
        assertEquals("77", player.getCurrentPlayerState().nowPlaying().music().id());
        verify(netease, times(1)).fetchPersonalFm();
        verify(netease, times(1)).getFmDjSongUrl("77");
        assertTrue(player.getPersistenceQueueSnapshot().isEmpty(), "FM provider state is not invented as a user queue item");
    }

    @Test void removingSelectedQueueItemCancelsPlaybackAndNeverRestoresItOnResume() {
        var item = enqueueReady("1");
        Sinks.One<PlayableMusic> media = Sinks.one();
        when(api.getPlayableMusic("1")).thenReturn(media.asMono());
        command(() -> player.togglePause("s"));
        command(() -> player.removeSongFromQueue(item.queueId(), "s"));
        media.tryEmitValue(playable("1"));
        executor.drain();
        assertNull(player.getCurrentPlayerState().nowPlaying());
        assertFalse(player.getCurrentPlayerState().isLoading());
        assertTrue(player.getPersistenceQueueSnapshot().isEmpty());
    }
}
