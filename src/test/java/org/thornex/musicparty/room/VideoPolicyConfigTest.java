package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.AdminConfigUpdateRequest;
import org.thornex.musicparty.dto.SystemConfigSnapshot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoPolicyConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminConfigUpdateRequest request(String cap, Integer duration) throws Exception {
        var json = mapper.createObjectNode();
        if (cap != null) json.put("videoMaxResolution", cap);
        if (duration != null) json.put("videoMaxDurationMinutes", duration);
        return mapper.treeToValue(json, AdminConfigUpdateRequest.class);
    }
    @Test void legacyConfigDefaultsUnlimitedAndRevisionStartsPerProcess() throws Exception {
        var repository = mock(RoomRepository.class);
        var defaults = new AppProperties();
        var old = SystemConfigSnapshot.from(defaults);
        var json = mapper.valueToTree(old);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("videoMaxResolution");
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("videoPolicyRevision", 77);
        when(repository.systemConfig()).thenReturn(mapper.writeValueAsString(json));
        var service = new SystemConfigService(defaults, repository, mapper, mock(RoomScope.class), mock(ApplicationContext.class));
        service.initialize();
        assertEquals("UNLIMITED", service.snapshot().videoMaxResolution());
        assertEquals(0L, service.snapshot().videoPolicyRevision());
    }
    @Test void configPublicationIsAtomicAndRoomOverridesCannotChangePolicy() throws Exception {
        var repository = mock(RoomRepository.class);
        var scope = mock(RoomScope.class);
        when(scope.activeIds()).thenReturn(java.util.Set.of());
        var service = new SystemConfigService(new AppProperties(), repository, mapper, scope, mock(ApplicationContext.class));
        service.initialize();
        var room = new AppProperties(); service.bind(room);
        service.update(request("720P", 60));
        assertEquals("720P", room.getPlayer().getVideoMaxResolution());
        assertEquals(1L, room.getPlayer().getVideoPolicyRevision());
        room.getPlayer().setVideoMaxResolution("UNLIMITED");
        assertEquals("720P", room.getPlayer().getVideoMaxResolution());
        var before = service.snapshot();
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.update(request("BAD", 30)));
        assertSame(before, service.snapshot());
        doThrow(new IllegalStateException("fixture save failure")).when(repository).saveSystemConfig(anyString(), anyInt());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.update(request("480P", 30)));
        assertSame(before, service.snapshot());
        assertEquals(60, room.getPlayer().getVideoMaxDurationMinutes());
    }
}
