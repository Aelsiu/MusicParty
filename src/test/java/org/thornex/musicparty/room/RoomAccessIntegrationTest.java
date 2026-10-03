package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.SpaRedirectController;
import org.thornex.musicparty.controller.RoomController;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.exception.GlobalExceptionHandler;
import org.thornex.musicparty.service.command.CommandSupport;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoomAccessIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper();
    private MultiRoomProperties properties;
    private RoomRepository repository;
    private RoomAccessService access;
    private MockMvc http;
    private String rootToken;

    @BeforeEach void setup() throws Exception {
        properties=new MultiRoomProperties();properties.setRootKey("AccessRoot9");
        properties.setDatabase(directory.resolve("rooms.sqlite").toString());
        properties.setLicenseFile(directory.resolve("licenses.json").toString());
        repository=new RoomRepository(properties,mapper);repository.initialize();
        // Start a fresh pairing period so HTTP/restart checks cannot straddle a wall-clock boundary.
        repository.saveSystemConfig("{}",9);
        controllers();
    }
    private void controllers() {
        access=new RoomAccessService(repository,properties);
        rootToken=access.login("AccessRoot9","root").token();
        http=MockMvcBuilders.standaloneSetup(new RoomController(repository,access,mock(RoomLifecycleService.class)),
                new SpaRedirectController()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() throws Exception {repository.close();}
    private RoomRepository.Room room(String owner) {return repository.create(owner,"访问测试🎵",UUID.randomUUID().toString());}
    private JsonNode response(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mapper.readTree(http.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void open(String roomId,boolean value,String manager) throws Exception {
        http.perform(patch("/api/rooms/{id}/access",roomId).header("Authorization","Bearer "+manager)
                        .contentType("application/json").content("{\"publicRoom\":"+value+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicRoom").value(value));
    }

    @Test void roomLookupAndBothAdmissionPathsRevealNoPairingOrOwnershipSecrets() throws Exception {
        var first=room("ROOT");var other=room("ROOT");
        http.perform(get("/api/rooms/{id}/access",first.id())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.id").value(first.id())).andExpect(jsonPath("$.publicRoom").value(false))
                .andExpect(jsonPath("$.pairingCode").doesNotExist()).andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.pairingOpen").doesNotExist());
        http.perform(post("/api/rooms/{id}/public-admission",first.id())).andExpect(status().isForbidden());
        http.perform(post("/api/rooms/{id}/join",first.id()).contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("code",other.pairingCode())))).andExpect(status().isForbidden());
        for(boolean publicRoom:new boolean[]{false,true}) {
            open(first.id(),publicRoom,rootToken);
            var paired=response(post("/api/rooms/{id}/join",first.id()).contentType("application/json")
                    .content(mapper.writeValueAsString(java.util.Map.of("code",repository.room(first.id()).pairingCode()))));
            assertEquals(first.id(),paired.path("room").path("id").asText());
            assertEquals(publicRoom,paired.path("room").path("publicRoom").asBoolean());
            assertFalse(paired.path("room").has("pairingCode"));assertFalse(paired.path("room").has("ownerId"));
            access.admission(paired.path("token").asText(),first.id());
            assertThrows(ResponseStatusException.class,()->access.admission(paired.path("token").asText(),other.id()));
        }
        var direct=response(post("/api/rooms/{id}/public-admission",first.id()));
        assertEquals(first.id(),direct.path("room").path("id").asText());
        assertFalse(direct.path("room").has("pairingCode"));assertTrue(direct.path("expiresAt").asLong()>System.currentTimeMillis());
        access.connect("direct",first.id(),direct.path("token").asText(),"");
        http.perform(get("/api/rooms/{id}/access","missing0")).andExpect(status().isNotFound());
    }

    @Test void onlyOwnerAndRootCanChangeOpenAndShowRemainsIndependent() throws Exception {
        var owner=repository.addLicense("OwnerKey9");var outsider=repository.addLicense("OtherKey9");var room=room(owner.id());
        String ownerToken=access.login(owner.key(),"owner").token(),otherToken=access.login(outsider.key(),"other").token();
        for(String token:new String[]{"",otherToken}) http.perform(patch("/api/rooms/{id}/access",room.id())
                .header("Authorization","Bearer "+token).contentType("application/json").content("{\"publicRoom\":true}"))
                .andExpect(status().isForbidden());
        http.perform(patch("/api/rooms/{id}/access",room.id()).header("Authorization","Bearer "+ownerToken)
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        open(room.id(),true,ownerToken);assertFalse(repository.room(room.id()).pairingOpen());
        http.perform(patch("/api/rooms/{id}/pairing",room.id()).header("Authorization","Bearer "+ownerToken)
                .contentType("application/json").content("{\"open\":true}")).andExpect(status().isOk());
        open(room.id(),false,rootToken);assertTrue(repository.room(room.id()).pairingOpen());
        http.perform(get("/api/rooms/{id}/manage",room.id()).header("Authorization","Bearer "+ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicRoom").value(false)).andExpect(jsonPath("$.pairingOpen").value(true));
    }

    @Test void reopeningAndRestartCannotResurrectAPublicGrantIssuedBeforeClosing() throws Exception {
        var room=room("ROOT");open(room.id(),true,rootToken);repository.setPairingOpen(room.id(),true);
        String publicToken=response(post("/api/rooms/{id}/public-admission",room.id())).path("token").asText();
        String pairedToken=access.join(room.id(),repository.room(room.id()).pairingCode(),"paired").token();
        access.connect("continuous",room.id(),publicToken,"");
        repository.close();repository=new RoomRepository(properties,mapper);repository.initialize();controllers();
        assertTrue(repository.room(room.id()).publicRoom());assertTrue(repository.room(room.id()).pairingOpen());
        access.admission(publicToken,room.id());
        access.connect("continuous",room.id(),publicToken,"");
        open(room.id(),false,rootToken);
        assertThrows(ResponseStatusException.class,()->access.admission(publicToken,room.id()));
        assertThrows(ResponseStatusException.class,()->access.connect("new",room.id(),publicToken,""));
        access.member(publicToken,"",room.id());access.connection("continuous");
        access.admission(pairedToken,room.id());
        open(room.id(),true,rootToken);
        assertThrows(ResponseStatusException.class,()->access.admission(publicToken,room.id()));
        String fresh=response(post("/api/rooms/{id}/public-admission",room.id())).path("token").asText();
        access.admission(fresh,room.id());
        open(room.id(),false,rootToken);
        repository.close();repository=new RoomRepository(properties,mapper);repository.initialize();controllers();
        assertFalse(repository.room(room.id()).publicRoom());
        assertThrows(ResponseStatusException.class,()->access.admission(fresh,room.id()));
        access.admission(pairedToken,room.id());
    }

    @Test void verificationPromotesOnlyTheMatchingLiveRoomConnectionAndRevokesWithTheLicense() throws Exception {
        var owner=repository.addLicense("OwnerKey9");var outsider=repository.addLicense("OtherKey9");
        var room=room(owner.id());var other=room(outsider.id());
        String admission=access.join(room.id(),room.pairingCode(),"guest").token();
        access.connect("user",room.id(),admission,"");access.connect("other-user",other.id(),access.join(other.pairingCode(),"other-user").token(),"");
        String manager=access.login(owner.key(),"owner").token();String outside=access.login(outsider.key(),"outside").token();
        for(String candidate:new String[]{outside,"invalid"}) http.perform(post("/api/rooms/{id}/owner",room.id())
                .header("Authorization","Bearer "+candidate).header("X-Room-Token",admission)
                .contentType("application/json").content("{\"sessionId\":\"user\"}")).andExpect(status().isForbidden());
        for(String session:new String[]{"other-user","absent"}) http.perform(post("/api/rooms/{id}/owner",room.id())
                .header("Authorization","Bearer "+manager).header("X-Room-Token",admission)
                .contentType("application/json").content("{\"sessionId\":\""+session+"\"}")).andExpect(status().isForbidden());
        http.perform(post("/api/rooms/{id}/owner",room.id()).header("Authorization","Bearer "+manager)
                .header("X-Room-Token","wrong").contentType("application/json").content("{\"sessionId\":\"user\"}"))
                .andExpect(status().isForbidden());
        assertEquals("",access.connection("user").managerToken());
        http.perform(post("/api/rooms/{id}/owner",room.id()).header("Authorization","Bearer "+manager)
                .header("X-Room-Token",admission).contentType("application/json").content("{\"sessionId\":\"user\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        var commands=new CommandSupport(access,mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        try(var ignored=RoomContext.enter(room.id())) {assertTrue(commands.requireManager(new User("profile","user","User")));}
        repository.replaceLicense(owner.id(),"NewOwner9");
        assertThrows(ResponseStatusException.class,()->access.connection("user"));
    }

    @Test void legacyRoomsAndAdmissionsMigrateToPrivateWithoutLosingPairingAccess() throws Exception {
        repository.close();properties.setDatabase(directory.resolve("legacy.sqlite").toString());
        String token="legacy-token";long now=System.currentTimeMillis();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+properties.getDatabase());var statement=db.createStatement()) {
            statement.execute("CREATE TABLE rooms(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner_id TEXT NOT NULL,created_at INTEGER NOT NULL,pair_code TEXT,pair_epoch INTEGER NOT NULL DEFAULT 0,payload TEXT,config TEXT,auto_open INTEGER NOT NULL DEFAULT 1,pairing_open INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE admissions(token TEXT PRIMARY KEY,room_id TEXT NOT NULL,epoch INTEGER NOT NULL,expires_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO rooms VALUES('apiABCDE','旧房间','ROOT',"+now+",'1234',"+RoomRepository.epoch(now)+",null,null,0,1)");
            statement.execute("INSERT INTO admissions VALUES('"+token+"','apiABCDE',"+RoomRepository.epoch(now)+","+((RoomRepository.epoch(now)+1)*600000)+")");
        }
        repository=new RoomRepository(properties,mapper);repository.initialize();controllers();
        assertFalse(repository.room("apiABCDE").publicRoom());assertTrue(repository.room("apiABCDE").pairingOpen());assertFalse(repository.room("apiABCDE").shareEnabled());
        access.admission(token,"apiABCDE");
        http.perform(post("/api/rooms/apiABCDE/public-admission")).andExpect(status().isForbidden());
    }

    @Test void allRoomIdPrefixesReachTheSpaWhileReservedEndpointsRemainAvailable() throws Exception {
        for(String id:new String[]{"apiABCDE","wsABCDEF","proxyABC","M9vkDrTt","ABCDEFGH"}) for(String suffix:new String[]{"","/"})
            http.perform(get("/"+id+suffix)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        // These services are absent from this standalone fixture; only assert the SPA did not capture them.
        for(String reserved:new String[]{"ws","api","proxy"}) http.perform(get("/"+reserved))
                .andExpect(result->assertNull(result.getHandler())).andExpect(result->assertNull(result.getResponse().getForwardedUrl()));
    }
}
