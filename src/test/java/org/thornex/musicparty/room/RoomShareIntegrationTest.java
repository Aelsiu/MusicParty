package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thornex.musicparty.controller.RoomController;
import org.thornex.musicparty.exception.GlobalExceptionHandler;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoomShareIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();
    private MultiRoomProperties properties;
    private RoomRepository repository;
    private RoomAccessService access;
    private RoomLifecycleService lifecycle;
    private MockMvc http;
    private RoomRepository.Room room, other;
    private String root, owner, outsider, member;

    @BeforeEach void setup() throws Exception {
        properties=new MultiRoomProperties();properties.setRootKey("ShareRoot9");
        properties.setDatabase(directory.resolve("rooms.sqlite").toString());
        properties.setLicenseFile(directory.resolve("licenses.json").toString());
        repository=new RoomRepository(properties,mapper);repository.initialize();repository.saveSystemConfig("{}",9);
        var license=repository.addLicense("OwnerKey9");var outside=repository.addLicense("OtherKey9");
        room=repository.create(license.id(),"分享测试🎵",UUID.randomUUID().toString());
        other=repository.create(outside.id(),"其他房间",UUID.randomUUID().toString());
        controllers();
        owner=access.login(license.key(),"owner").token();outsider=access.login(outside.key(),"outside").token();
        member=access.join(room.id(),room.pairingCode(),"member").token();
    }
    private void controllers() {
        access=new RoomAccessService(repository,properties);root=access.login("ShareRoot9","root").token();
        lifecycle=mock(RoomLifecycleService.class);
        http=MockMvcBuilders.standaloneSetup(new RoomController(repository,access,lifecycle))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() throws Exception { repository.close(); }
    private JsonNode response(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mapper.readTree(http.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    private void enabled(boolean value,String manager) throws Exception {
        http.perform(patch("/api/rooms/{id}/share",room.id()).header("Authorization","Bearer "+manager)
                .contentType("application/json").content("{\"enabled\":"+value+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shareEnabled").value(value));
    }

    @Test void sharingDefaultsOffAndNeitherShowNorPublicEntryBypassIt() throws Exception {
        assertFalse(room.shareEnabled());
        assertEquals(mapper.valueToTree(Map.of("enabled",false)),response(get("/api/rooms/{id}/share",room.id()).header("X-Room-Token",member)));
        for(String manager:new String[]{root,owner}) http.perform(get("/api/rooms/{id}/invite",room.id())
                .header("Authorization","Bearer "+manager)).andExpect(status().isForbidden());
        http.perform(get("/api/rooms/{id}/share",room.id())).andExpect(status().isForbidden());
        repository.setPairingOpen(room.id(),true);repository.setPublicRoom(room.id(),true);
        var publicMember=access.publicAdmission(room.id(),"public");
        http.perform(get("/api/rooms/{id}/invite",room.id()).header("X-Room-Token",publicMember.token())).andExpect(status().isForbidden());
        assertFalse(repository.room(room.id()).shareEnabled());
    }

    @Test void onlyOwningManagersToggleAndMissingStateIsRejected() throws Exception {
        http.perform(patch("/api/rooms/{id}/share",room.id()).header("X-Room-Token",member)
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        http.perform(patch("/api/rooms/{id}/share",room.id()).header("Authorization","Bearer "+outsider)
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        http.perform(patch("/api/rooms/{id}/share",room.id()).header("Authorization","Bearer "+owner)
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        enabled(true,owner);enabled(false,root);
        verify(lifecycle,times(2)).shareChanged(room.id());
        assertFalse(repository.room(room.id()).pairingOpen());assertFalse(repository.room(room.id()).publicRoom());
    }

    @Test void invitationsAreFreshForMembersAndManagersAndCloseImmediatelyForEverybody() throws Exception {
        enabled(true,owner);
        for(String manager:new String[]{owner,root}) assertEquals(room.pairingCode(),response(get("/api/rooms/{id}/invite",room.id())
                .header("Authorization","Bearer "+manager)).path("pairingCode").asText());
        access.connect("member",room.id(),member,"");
        repository.saveSystemConfig("{}",8);
        String fresh=repository.room(room.id()).pairingCode();assertNotEquals(room.pairingCode(),fresh);
        JsonNode invite=response(get("/api/rooms/{id}/invite",room.id()).header("X-Room-Token",member));
        assertEquals(4,invite.size());assertEquals(fresh,invite.path("pairingCode").asText());
        assertEquals(8,invite.path("pairingIntervalMinutes").asInt());
        assertTrue(invite.path("nextUpdateAt").asLong()>invite.path("serverTime").asLong());
        assertEquals(mapper.valueToTree(Map.of("open",false)),response(get("/api/rooms/{id}/pairing",room.id()).header("X-Room-Token",member)));
        enabled(false,owner);
        http.perform(get("/api/rooms/{id}/invite",room.id()).header("X-Room-Token",member)).andExpect(status().isForbidden());
        for(String manager:new String[]{owner,root}) http.perform(get("/api/rooms/{id}/invite",room.id())
                .header("Authorization","Bearer "+manager)).andExpect(status().isForbidden());
        assertEquals(fresh,repository.room(room.id()).pairingCode());
        access.member(member,"",room.id());
    }

    @Test void enabledInvitationsStillRequireTheAddressedRoomAndNeverUseQueryCredentials() throws Exception {
        enabled(true,root);repository.setShareEnabled(other.id(),true);
        http.perform(get("/api/rooms/{id}/invite",other.id()).header("X-Room-Token",member)).andExpect(status().isForbidden());
        http.perform(get("/api/rooms/{id}/invite",room.id()).header("Authorization","Bearer "+outsider)).andExpect(status().isForbidden());
        for(String credential:new String[]{room.pairingCode(),"OwnerKey9",root}) http.perform(get("/api/rooms/{id}/invite",room.id())
                .param("pcd",credential).param("key",credential).param("managerToken",credential)).andExpect(status().isForbidden());
        repository.replaceLicense(room.ownerId(),"NewOwner9");
        http.perform(get("/api/rooms/{id}/invite",room.id()).header("Authorization","Bearer "+owner)).andExpect(status().isForbidden());
    }

    @Test void shareMetadataAndSettingSurviveRestartWithoutExposingCode() throws Exception {
        enabled(true,owner);repository.setPairingOpen(room.id(),true);
        JsonNode metadata=response(get("/api/rooms/{id}/access",room.id()));
        assertTrue(metadata.path("shareEnabled").asBoolean());assertFalse(metadata.has("pairingCode"));assertFalse(metadata.has("ownerId"));
        repository.close();repository=new RoomRepository(properties,mapper);repository.initialize();controllers();
        assertTrue(repository.room(room.id()).shareEnabled());assertTrue(repository.room(room.id()).pairingOpen());
        assertEquals(mapper.valueToTree(Map.of("enabled",true)),response(get("/api/rooms/{id}/share",room.id()).header("X-Room-Token",member)));
    }
}
