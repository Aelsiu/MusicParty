package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MusicPlayerServiceIdleKickTest {
    @Test
    void kicksConnectedUsersOnlyAfterConfiguredNoMusicInterval() {
        AppProperties properties = new AppProperties();
        properties.getPlayer().setIdleKickEnabled(true);
        properties.getPlayer().setIdleKickMinutes(1);
        UserService users = mock(UserService.class);
        when(users.getOnlineUserSummaries()).thenReturn(List.of(new UserSummary("token", "session", "listener", false)));
        MusicQueueManager queue = mock(MusicQueueManager.class);
        when(queue.getQueueSnapshot()).thenReturn(List.of());
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        MusicPlayerService player = new MusicPlayerService(List.of(), users,
                mock(LocalCacheService.class), mock(LiveStreamService.class), queue, events,
                properties, mock(NeteaseMusicApiService.class), mock(PrivateDjService.class));

        player.checkIdleKick(1_000);
        player.checkIdleKick(60_000);
        verify(users, never()).kickOnlineUsersForIdle();

        player.checkIdleKick(61_000);
        verify(users).kickOnlineUsersForIdle();
        verify(events).publishEvent(any(SystemMessageEvent.class));
    }
}
