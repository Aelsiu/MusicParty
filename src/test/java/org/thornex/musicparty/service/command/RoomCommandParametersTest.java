package org.thornex.musicparty.service.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.room.RoomContext;
import org.thornex.musicparty.room.RoomRepository;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.stream.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RoomCommandParametersTest {
    private final User user = new User("profile", "sending-session", "测试");
    private final CommandSupport support = mock(CommandSupport.class);
    private final MusicPlayerService player = mock(MusicPlayerService.class);
    private final QueuePersistenceService persistence = mock(QueuePersistenceService.class);

    @ParameterizedTest @CsvSource({"all,QUEUE", "dead,OFFLINE", "chat,CHAT"})
    void managerCleanupOnlyRequestsConfirmationAndNeverDeletesBeforeConfirm(String parameter, String target) {
        MusicQueueManager queue = mock(MusicQueueManager.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(support.requireManager(user)).thenReturn(true);
        new ClearCommand(queue, player, publisher, support).execute(parameter, user);
        verify(support).event(user, "CLEAR_CONFIRM", "CONFIRM_CLEAR", target);
        verifyNoInteractions(queue, player, publisher);
    }

    @Test void userCannotClearOtherSongsAndDefaultOnlyRemovesOwnSongs() {
        MusicQueueManager queue = mock(MusicQueueManager.class);
        var command = new ClearCommand(queue, player, mock(ApplicationEventPublisher.class), support);
        for (String parameter : new String[]{"all", "dead", "chat"}) command.execute(parameter, user);
        verifyNoInteractions(queue, player);
        verify(support, never()).event(any(), anyString(), anyString(), any());
        command.execute("", user);
        command.execute("SELF", user);
        verify(queue, times(2)).removeByUser("profile");
    }

    @ParameterizedTest @CsvSource({"on,ALL", "off,DISABLED", "dog,OWNER_AND_ENQUEUER", "'',DISABLED"})
    void jumpUpdatesTheSameRoomSettingAndPersists(String parameter, String policy) {
        var properties = new AppProperties();
        when(support.requireManager(user)).thenReturn(true);
        new JumpCommand(support, properties, player, persistence).execute(parameter, user);
        assertEquals(policy, properties.getPlayer().getSeekPolicy());
        verify(player).broadcastFullPlayerState();
        verify(persistence).saveNow();
    }

    @Test void jumpDoesNotMutateForUserOrInvalidParameters() {
        var properties = new AppProperties();
        String original = properties.getPlayer().getSeekPolicy();
        var command = new JumpCommand(support, properties, player, persistence);
        command.execute("on", user);
        command.execute("unknown", user);
        assertEquals(original, properties.getPlayer().getSeekPolicy());
        verifyNoInteractions(player, persistence);
    }

    @Test void streamOnAndOffRequireManagerAndPersist() {
        var stream = mock(LiveStreamService.class);
        var command = new StreamCommand(mock(StreamTokenService.class), stream, mock(SimpMessagingTemplate.class), new AppProperties(), support, player, persistence);
        command.execute("on", user);
        verifyNoInteractions(stream, player, persistence);
        when(support.requireManager(user)).thenReturn(true);
        command.execute("ON", user); command.execute("off", user);
        verify(stream).setEnabled(true); verify(stream).setEnabled(false);
        verify(player, times(2)).broadcastFullPlayerState();
        verify(persistence, times(2)).saveNow();
    }

    @ParameterizedTest @CsvSource({"on,true", "ON,true", "off,false", "OFF,false"})
    void pairingOnAndOffOnlyChangeCurrentRoomAndBroadcastVisibilityFlag(String parameter, boolean open) {
        var repository = mock(RoomRepository.class);
        var messaging = mock(SimpMessagingTemplate.class);
        when(support.requireManager(user)).thenReturn(true);
        try (var ignored = RoomContext.enter("Room1234")) {
            new PairingCodeCommand(messaging, support, repository).execute(parameter, user);
        }
        verify(repository).setPairingOpen("Room1234", open);
        verify(messaging).convertAndSend("/topic/rooms/Room1234/pairing", java.util.Map.of("open", open));
        verify(support).reply(user, open ? "已开启房内配对码展示" : "已关闭房内配对码展示");
        verifyNoMoreInteractions(repository, messaging);
    }

    @Test void ordinaryUsersCannotTogglePairingVisibility() {
        var repository = mock(RoomRepository.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var command = new PairingCodeCommand(messaging, support, repository);
        command.execute("on", user);
        command.execute("off", user);
        verify(support, times(2)).requireManager(user);
        verifyNoInteractions(repository, messaging);
    }

    @Test void removedOpenParameterAndInvalidPairingArgumentsNeverMutateVisibility() {
        var repository = mock(RoomRepository.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var command = new PairingCodeCommand(messaging, support, repository);
        for (String parameter : new String[]{"open", "on extra", "off extra", "unknown"}) {
            command.execute(parameter, user);
        }
        verify(support, times(4)).reply(user, "用法：//code [copy|on|off]，不带参数默认 copy");
        verify(support, never()).requireManager(user);
        verifyNoInteractions(repository, messaging);
    }

    @ParameterizedTest @CsvSource({"''", "now"})
    void personalStreamLinkIsPrivateToTheSendingSessionAndIncludesRoom(String parameter) {
        var tokens = mock(StreamTokenService.class);
        var stream = mock(LiveStreamService.class);
        var messaging = mock(SimpMessagingTemplate.class);
        when(stream.isEnabled()).thenReturn(true);
        when(tokens.generateToken("profile")).thenReturn("personal-key");
        try (var ignored = RoomContext.enter("Room1234")) {
            new StreamCommand(tokens, stream, messaging, new AppProperties(), support, player, persistence).execute(parameter, user);
        }
        var message = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging).convertAndSendToUser(eq("sending-session"), eq("/queue/chat/private"), message.capture(), anyMap());
        assertTrue(message.getValue().content().contains("roomId=Room1234&key=personal-key"));
        verifyNoInteractions(support, player, persistence);
    }
}
