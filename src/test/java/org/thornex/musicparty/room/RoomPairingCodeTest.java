package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoomPairingCodeTest {
    @TempDir Path directory;
    private MultiRoomProperties properties;
    private RoomRepository repository;
    private RoomAccessService access;

    @BeforeEach void setup() throws Exception {
        properties=new MultiRoomProperties();properties.setRootKey("PairRoot9");
        properties.setDatabase(directory.resolve("rooms.sqlite").toString());
        properties.setLicenseFile(directory.resolve("licenses.json").toString());
        repository=new RoomRepository(properties,new ObjectMapper());repository.initialize();repository.saveSystemConfig("{}",9);
        access=new RoomAccessService(repository,properties);
    }
    @AfterEach void close() throws Exception { repository.close(); }

    @Test void bothPairingEntryPathsIgnoreAsciiLetterCaseAndKeepLicenseCaseSensitivity() throws Exception {
        var room=repository.create("ROOT","字符配对",UUID.randomUUID().toString());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+properties.getDatabase());
            var update=db.prepareStatement("UPDATE rooms SET pair_code=? WHERE id=?")) {
            update.setString(1,"a1b2");update.setString(2,room.id());update.executeUpdate();
        }
        assertEquals(room.id(),access.join("A1B2","root-path").roomId());
        assertEquals(room.id(),access.join(room.id(),"a1B2","room-path").roomId());
        for(String invalid:List.of("abc","abcde","a_b2","a b2","ａ1b2","а1b2","a1b!","a1b2 ","PairRoot9")) {
            assertThrows(ResponseStatusException.class,()->access.join(invalid,"invalid-"+invalid));
            assertThrows(ResponseStatusException.class,()->access.join(room.id(),invalid,"room-invalid-"+invalid));
        }
        assertThrows(ResponseStatusException.class,()->access.join(null,"null"));
        assertThrows(ResponseStatusException.class,()->access.login("pairroot9","wrong-case"));
        assertTrue(access.login("PairRoot9","correct-case").root());
        var other=repository.create("ROOT","别的房间",UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class,()->access.join(other.id(),"A1B2","wrong-room"));
        repository.saveSystemConfig("{}",8);
        assertThrows(ResponseStatusException.class,()->access.join("A1B2","retired"));
    }

    @Test void generatedCodesStayFourLowercaseAsciiCharactersAcrossRotationAndRemainCaseInsensitive() {
        for(int i=0;i<9;i++) repository.create("ROOT","生成测试",UUID.randomUUID().toString());
        for(var room:repository.rooms()) {
            assertTrue(room.pairingCode().matches("[a-z0-9]{4}"));
            assertEquals(room.id(),access.join(room.pairingCode().toUpperCase(Locale.ROOT),"generated-"+room.id()).roomId());
        }
        var retired=repository.rooms().stream().map(RoomRepository.Room::pairingCode).toList();
        repository.saveSystemConfig("{}",8);
        var current=repository.rooms().stream().map(RoomRepository.Room::pairingCode).toList();
        assertEquals(9,current.stream().distinct().count());
        assertTrue(current.stream().allMatch(code->code.matches("[a-z0-9]{4}")));
        assertTrue(java.util.Collections.disjoint(retired,current));
    }
}
