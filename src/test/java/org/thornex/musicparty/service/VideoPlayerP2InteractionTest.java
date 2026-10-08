package org.thornex.musicparty.service;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.service.api.BilibiliVideoApiService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class VideoPlayerP2InteractionTest {
    @Test void skipLockBlocksMembershipAndTickEvaluationAndUnlockUsesCurrentMediaPosition() {
        var f=new VideoPlayerServiceTest.Fixture(); f.properties.getPlayer().setVoteSkipWaitTime(0); f.properties.getPlayer().setVoteSkipEnabled(true);
        User b=new User("b","b-session","B"); when(f.users.getUser("b-session")).thenReturn(Optional.of(b));
        when(f.users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner","b","c"));
        when(f.users.getOnlineUserSummaries()).thenReturn(List.of(new UserSummary("owner","owner-session","Owner",false),new UserSummary("b","b-session","B",false),new UserSummary("c","c-session","C",false)));
        f.properties.getPlayer().setVoteSkipThreshold(0.75); f.enqueue("BV1GJ411x7h7"); f.enqueue("BV1Q541167Qg");
        f.act(() -> f.player.togglePause("SYSTEM")); f.advance(1000); f.act(() -> f.player.skipToNext("b-session"));
        assertTrue((Boolean)f.player.interaction("b-session").get("hasVoted")); f.act(() -> f.player.setLock("SKIP",true));
        when(f.users.getRecentlyActiveUserTokens()).thenReturn(Set.of("owner","b"));
        when(f.users.getOnlineUserSummaries()).thenReturn(List.of(new UserSummary("owner","owner-session","Owner",false),new UserSummary("b","b-session","B",false)));
        f.act(f.player::membersChanged); f.act(f.player::playerLoop); assertEquals("BV1GJ411x7h7:p1",f.state().nowPlaying().music().id());
        f.properties.getPlayer().setVoteSkipWaitTime(15); f.act(() -> f.player.setAllLocks(false));
        assertEquals("BV1GJ411x7h7:p1",f.state().nowPlaying().music().id()); f.advance(15000); f.act(f.player::playerLoop);
        assertEquals("BV1Q541167Qg:p1",f.state().nowPlaying().music().id());
    }
    @Test void crossingMediaVoteGateMarksStateDirtyWithoutWaitingForHeartbeat() {
        var f=new VideoPlayerServiceTest.Fixture(); f.properties.getPlayer().setVoteSkipWaitTime(2);
        f.enqueue("BV1GJ411x7h7"); f.act(() -> f.player.togglePause("SYSTEM")); f.sink.take(org.thornex.musicparty.enums.MediaMode.VIDEO);
        f.advance(1999); f.act(f.player::playerLoop); assertFalse(f.sink.take(org.thornex.musicparty.enums.MediaMode.VIDEO).stateDirty());
        f.advance(1); f.act(f.player::playerLoop); assertTrue(f.sink.take(org.thornex.musicparty.enums.MediaMode.VIDEO).stateDirty());
    }
    @Test void historyRevisionIsContentBasedAndRestoreAndResetAdvanceIt() {
        var f=new VideoPlayerServiceTest.Fixture(); long first=f.player.getHistoryRevision();
        f.act(() -> f.player.restore(List.of(),List.of(BilibiliVideoApiService.toMusic(f.descriptor("BV1GJ411x7h7")))));
        long restored=f.player.getHistoryRevision(); assertTrue(restored>first); assertEquals(restored,f.player.getHistoryRevision());
        f.properties.getQueue().setHistorySize(0); f.act(f.player::trimHistoryToLimit);
        long trimmed=f.player.getHistoryRevision(); assertTrue(trimmed>restored); assertTrue(f.player.getHistorySnapshot().isEmpty());
        f.act(f.player::resetSystem); assertTrue(f.player.getHistoryRevision()>trimmed);
    }
    @Test void importReservationsCompeteWithSingleAdmissionsAndReleaseExactlyOnce() {
        var f=new VideoPlayerServiceTest.Fixture(); f.properties.getQueue().setMaxSize(2); f.properties.getQueue().setMaxUserSongs(2);
        String id=f.mailbox.submit(() -> f.player.reserveImportSlots("owner",2)).join();
        assertEquals(0,f.mailbox.submit(() -> f.player.availableImportSlots("owner")).join());
        assertThrows(RuntimeException.class,() -> f.enqueue("BV1GJ411x7h7"));
        f.act(() -> f.player.releaseImportSlots(id)); f.act(() -> f.player.releaseImportSlots(id));
        assertEquals(2,f.mailbox.submit(() -> f.player.availableImportSlots("owner")).join()); f.enqueue("BV1GJ411x7h7");
        assertEquals(1,f.state().queue().size());
    }
}
