package org.thornex.musicparty.service.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.PlayerEvent;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.room.*;
import org.thornex.musicparty.service.ChatService;
import org.thornex.musicparty.service.UserService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShareCommandTest {
    private final RoomRepository repository=mock(RoomRepository.class);
    private final RoomLifecycleService lifecycle=mock(RoomLifecycleService.class);
    private final SimpMessagingTemplate messaging=mock(SimpMessagingTemplate.class);
    private final RoomAccessService access=mock(RoomAccessService.class);
    private final CommandSupport support=new CommandSupport(access,messaging);
    private final ShareCommand command=new ShareCommand(support,repository,lifecycle);
    private final User sender=new User("shared-profile","sending-tab","Owner");

    private void manager() {
        manager("ROOT");
    }
    private void manager(String licenseId) {
        when(access.connection("sending-tab")).thenReturn(new RoomAccessService.Connection("Room1234","manager","shared-profile"));
        when(access.own("manager","Room1234")).thenReturn(new RoomAccessService.Manager("manager",licenseId,"version",Long.MAX_VALUE));
    }
    private ChatService chat() {
        var users=mock(UserService.class);
        when(users.getUser("sending-tab")).thenReturn(Optional.of(new User("shared-profile","another-tab","Owner")));
        return new ChatService(messaging,users,new AppProperties(),List.of(command));
    }

    @Test void copyTriggerIsPrivateToTheSendingTabAndContainsNoPairingCodeOrHistoryEntry() {
        manager();var room=mock(RoomRepository.Room.class);when(room.shareEnabled()).thenReturn(true);when(repository.room("Room1234")).thenReturn(room);
        var chat=chat();
        try(var ignored=RoomContext.enter("Room1234")) { assertTrue(chat.processIncomingMessage("sending-tab","//SHARE")); }
        var event=ArgumentCaptor.forClass(PlayerEvent.class);var headers=ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messaging).convertAndSendToUser(eq("sending-tab"),eq("/queue/events"),event.capture(),headers.capture());
        assertEquals("SHARE_TRIGGER",event.getValue().action());assertEquals("COPY_ROOM_INVITE",event.getValue().message());
        assertNull(event.getValue().payload());assertEquals("shared-profile",event.getValue().userId());
        assertEquals("sending-tab",SimpMessageHeaderAccessor.getSessionId(headers.getValue()));
        assertTrue(chat.getHistoryFull().isEmpty());verify(room,never()).pairingCode();verifyNoMoreInteractions(messaging);
    }

    @ParameterizedTest
    @ValueSource(strings={"ROOT","owner-license"})
    void onAndOffChangeOnlySharingAndTheirConfirmationsStayPrivate(String licenseId) {
        manager(licenseId);var chat=chat();
        try(var ignored=RoomContext.enter("Room1234")) {
            assertTrue(chat.processIncomingMessage("sending-tab","//SHARE ON"));
            assertTrue(chat.processIncomingMessage("sending-tab","//share off"));
        }
        verify(repository).setShareEnabled("Room1234",true);verify(repository).setShareEnabled("Room1234",false);
        verify(repository,never()).setPairingOpen(anyString(),anyBoolean());verify(repository,never()).setPublicRoom(anyString(),anyBoolean());
        verify(lifecycle,times(2)).shareChanged("Room1234");
        var replies=ArgumentCaptor.forClass(ChatMessage.class);var headers=ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messaging,times(2)).convertAndSendToUser(eq("sending-tab"),eq("/queue/chat/private"),replies.capture(),headers.capture());
        assertEquals(List.of("已开启房间分享","已关闭房间分享"),replies.getAllValues().stream().map(ChatMessage::content).toList());
        assertTrue(headers.getAllValues().stream().allMatch(value->"sending-tab".equals(SimpMessageHeaderAccessor.getSessionId(value))));
        assertTrue(chat.getHistoryFull().isEmpty());verifyNoMoreInteractions(messaging);
    }

    @Test void ordinaryUsersCanCopyEnabledInvitesPrivatelyWithoutManagerCredentials() {
        var room=mock(RoomRepository.Room.class);when(room.shareEnabled()).thenReturn(true);when(repository.room("Room1234")).thenReturn(room);
        var chat=chat();
        try(var ignored=RoomContext.enter("Room1234")) { assertTrue(chat.processIncomingMessage("sending-tab","//share")); }
        var event=ArgumentCaptor.forClass(PlayerEvent.class);var headers=ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messaging).convertAndSendToUser(eq("sending-tab"),eq("/queue/events"),event.capture(),headers.capture());
        assertEquals("SHARE_TRIGGER",event.getValue().action());assertEquals("COPY_ROOM_INVITE",event.getValue().message());
        assertNull(event.getValue().payload());assertEquals("sending-tab",SimpMessageHeaderAccessor.getSessionId(headers.getValue()));
        assertTrue(chat.getHistoryFull().isEmpty());verify(room,never()).pairingCode();
        verify(repository).room("Room1234");verifyNoMoreInteractions(repository,messaging);verifyNoInteractions(access,lifecycle);
    }

    @Test void ordinaryUsersCannotCopyWhenSharingIsDisabled() {
        var room=mock(RoomRepository.Room.class);when(repository.room("Room1234")).thenReturn(room);
        var chat=chat();
        try(var ignored=RoomContext.enter("Room1234")) { assertTrue(chat.processIncomingMessage("sending-tab","//share")); }
        var replies=ArgumentCaptor.forClass(ChatMessage.class);var headers=ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messaging).convertAndSendToUser(eq("sending-tab"),eq("/queue/chat/private"),replies.capture(),headers.capture());
        assertTrue(replies.getValue().content().contains("已关闭分享"));
        assertTrue(replies.getValue().content().contains("请联系管理员"));
        assertEquals("sending-tab",SimpMessageHeaderAccessor.getSessionId(headers.getValue()));assertTrue(chat.getHistoryFull().isEmpty());
        verify(repository).room("Room1234");verifyNoMoreInteractions(repository,messaging);verifyNoInteractions(access,lifecycle);
    }

    @Test void ordinaryUsersCannotToggleAndOnlyReceivePrivateDenials() {
        when(access.connection("sending-tab")).thenReturn(new RoomAccessService.Connection("Room1234","","shared-profile"));
        try(var ignored=RoomContext.enter("Room1234")) { for(String args:List.of("on","off")) command.execute(args,sender); }
        verifyNoInteractions(repository,lifecycle);verify(access,never()).own(anyString(),anyString());
        var replies=ArgumentCaptor.forClass(ChatMessage.class);var headers=ArgumentCaptor.forClass(MessageHeaders.class);
        verify(messaging,times(2)).convertAndSendToUser(eq("sending-tab"),eq("/queue/chat/private"),replies.capture(),headers.capture());
        assertTrue(replies.getAllValues().stream().allMatch(message->message.content().contains("仅限本房间 Owner 或 Root")));
        assertTrue(headers.getAllValues().stream().allMatch(value->"sending-tab".equals(SimpMessageHeaderAccessor.getSessionId(value))));
        verifyNoMoreInteractions(messaging);
    }

    @Test void closedSharingAndMalformedArgumentsNeverTriggerCopyOrMutateState() {
        manager();var room=mock(RoomRepository.Room.class);when(repository.room("Room1234")).thenReturn(room);
        try(var ignored=RoomContext.enter("Room1234")) {
            command.execute("",sender);
            for(String args:List.of("copy","on extra","off extra","unknown")) command.execute(args,sender);
        }
        verify(repository).room("Room1234");verifyNoMoreInteractions(repository);verifyNoInteractions(lifecycle);
        var replies=ArgumentCaptor.forClass(ChatMessage.class);
        verify(messaging,times(5)).convertAndSendToUser(eq("sending-tab"),eq("/queue/chat/private"),replies.capture(),anyMap());
        assertTrue(replies.getAllValues().get(0).content().contains("//share on"));
        assertTrue(replies.getAllValues().subList(1,5).stream().allMatch(message->message.content().startsWith("用法：")));
        verifyNoMoreInteractions(messaging);
    }
}
