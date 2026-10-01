package org.thornex.musicparty.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultiRoomAccessTest {
    @TempDir Path directory;
    private RoomRepository repository;
    private RoomAccessService access;
    private MultiRoomProperties config;
    @BeforeEach void setup() throws Exception {
        config=new MultiRoomProperties();config.setRootKey("TestRoot9");config.setDatabase(directory.resolve("rooms.sqlite").toString());config.setLicenseFile(directory.resolve("licenses.json").toString());
        repository=new RoomRepository(config,new ObjectMapper());repository.initialize();access=new RoomAccessService(repository,config);
    }
    @AfterEach void close() throws Exception { repository.close(); }
    @Test void emptyStartupAndRootKeyRequired() throws Exception {
        assertTrue(repository.rooms().isEmpty());
        var invalid=new MultiRoomProperties();assertThrows(IllegalStateException.class,()->new RoomRepository(invalid,new ObjectMapper()).initialize());
    }
    @Test void manuallyRemovedLicenseDeletesItsRoomsAndCacheOnNextStartup() throws Exception {
        var license=repository.addLicense("OwnerKey9");var room=repository.create(license.id(),"删除🎵",UUID.randomUUID().toString());
        var cache=java.nio.file.Path.of("cached_media","rooms",room.id());java.nio.file.Files.createDirectories(cache);
        java.nio.file.Files.writeString(cache.resolve("fixture.txt"),"isolated test cache");
        repository.close();java.nio.file.Files.writeString(java.nio.file.Path.of(config.getLicenseFile()),"[]");
        repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        assertFalse(repository.exists(room.id()));assertFalse(java.nio.file.Files.exists(cache));
    }
    @Test void licenseReplacementPreservesOwnershipAndRevokesSessions() {
        var license=repository.addLicense("OwnerKey9");var owner=access.login("OwnerKey9","one");
        var room=repository.create(license.id(),"电台🎵",UUID.randomUUID().toString());
        repository.replaceLicense(license.id(),"NewOwner9");
        assertEquals(license.id(),repository.room(room.id()).ownerId());
        assertThrows(ResponseStatusException.class,()->access.manager(owner.token()));
        assertThrows(ResponseStatusException.class,()->access.login("OwnerKey9","two"));
        assertEquals(license.id(),access.login("NewOwner9","three").licenseId());
        assertThrows(ResponseStatusException.class,()->repository.addLicense("NewOwner9"));
        assertEquals(List.of(room.id()),repository.removeLicense(license.id()));assertFalse(repository.exists(room.id()));
    }
    @Test void licenseNotesPersistAndReplacementPreservesNoteWithoutRevokingNoteEdits() throws Exception {
        var license=repository.addLicense("OwnerKey9","杭州🎵");var manager=access.login("OwnerKey9","one");
        repository.updateLicenseNote(license.id(),"👨‍👩‍👧‍👦".repeat(16));
        assertEquals(license.id(),access.manager(manager.token()).licenseId());
        assertThrows(ResponseStatusException.class,()->repository.updateLicenseNote(license.id(),"🎵".repeat(17)));
        assertThrows(ResponseStatusException.class,()->repository.updateLicenseNote(license.id(),"a\nb"));
        assertThrows(ResponseStatusException.class,()->repository.updateLicenseNote(license.id(),"a\u200bb"));
        repository.replaceLicense(license.id(),"NewOwner9");repository.close();
        repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        assertEquals("👨‍👩‍👧‍👦".repeat(16),repository.license(license.id()).orElseThrow().note());
        assertEquals("NewOwner9",repository.license(license.id()).orElseThrow().key());
        repository.updateLicenseNote(license.id(),"");assertEquals("",repository.license(license.id()).orElseThrow().note());
    }
    @Test void ownRoomLimitAndIdempotentCreationApplyToRootAndOrdinaryLicenses() {
        for(String owner:List.of("ROOT",repository.addLicense("OwnerKey9").id())) {
            String request=UUID.randomUUID().toString();var first=repository.create(owner,"同名🎵",request);
            assertEquals(first.id(),repository.create(owner,"同名🎵",request).id());
            for(int i=1;i<9;i++)repository.create(owner,"同名🎵",UUID.randomUUID().toString());
            assertThrows(ResponseStatusException.class,()->repository.create(owner,"第十间",UUID.randomUUID().toString()));
        }
        assertEquals(18,repository.rooms().size());assertEquals(18,repository.rooms().stream().map(RoomRepository.Room::id).distinct().count());
    }
    @Test void roomAuthorizationAndSubscriptionsCannotCrossRooms() {
        var a=repository.addLicense("OwnerKey9");var b=repository.addLicense("OtherKey9");
        var first=repository.create(a.id(),"第一间",UUID.randomUUID().toString());var second=repository.create(b.id(),"第二间",UUID.randomUUID().toString());
        var owner=access.login("OwnerKey9","one");assertThrows(ResponseStatusException.class,()->access.own(owner.token(),second.id()));
        var admission=access.join(first.pairingCode(),"join");access.admission(admission.token(),first.id());assertThrows(ResponseStatusException.class,()->access.admission(admission.token(),second.id()));
        assertEquals("ROOT",access.own(access.login("TestRoot9","root").token(),second.id()).licenseId());
    }
    @Test void rotationIsAtomicUniqueCooledAndSurvivesRestart() throws Exception {
        for(int i=0;i<9;i++)repository.create("ROOT","房间🎵",UUID.randomUUID().toString());
        Set<String> retired=new HashSet<>();long now=System.currentTimeMillis();
        for(int i=0;i<3;i++){Set<String> codes=new HashSet<>();for(var r:repository.rooms())codes.add(r.pairingCode());assertEquals(9,codes.size());retired.addAll(codes);repository.rotate(now+(i+1)*600000);for(var r:repository.rooms())assertFalse(retired.contains(r.pairingCode()));}
        var saved=repository.rooms();repository.close();repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        assertEquals(saved.stream().map(RoomRepository.Room::id).toList(),repository.rooms().stream().map(RoomRepository.Room::id).toList());
    }
    @Test void unicodeNamesUseVisibleGraphemesAndPreserveEmojiJoiners() {
        for(String name:List.of("中文","🌙🎵","🇨🇳🇨🇦","👨‍👩‍👧‍👦👩🏽‍💻","🎵".repeat(16)))assertTrue(RoomValidation.name(name),name);
        for(String name:List.of("🎵","🎵".repeat(17)," \t","\u00a0\u00a0","a\nb","a\u200bb","a\u200Db","😀\u200da","😀"+new String(Character.toChars(0xE0061))+"b","\u0301\u0302"))assertFalse(RoomValidation.name(name),name);
    }
    @Test void admissionAndCreationRetrySurviveRestartWithinTheSamePairingPeriod() throws Exception {
        String request=UUID.randomUUID().toString();
        var room=repository.create("ROOT","恢复🎵",request);
        var guest=access.join(room.pairingCode(),"guest");
        repository.close();repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        access=new RoomAccessService(repository,config);
        assertEquals(room.id(),repository.create("ROOT","恢复🎵",request).id());
        access.admission(guest.token(),room.id());
        assertEquals(1,repository.rooms().size());
    }
    @Test void expiredAdmissionKeepsAnExistingConnectionButCannotOpenAnotherConnection() {
        var room=repository.create("ROOT","连接🎵",UUID.randomUUID().toString());
        var guest=access.join(room.pairingCode(),"guest");
        access.connect("existing",room.id(),guest.token(),"");
        @SuppressWarnings("unchecked")
        Map<String,RoomAccessService.Admission> values=(Map<String,RoomAccessService.Admission>)org.springframework.test.util.ReflectionTestUtils.getField(access,"admissions");
        values.put(guest.token(),new RoomAccessService.Admission(guest.token(),room.id(),guest.epoch()-1,guest.expiresAt()-600000));
        access.connection("existing");access.member(guest.token(),"",room.id());
        assertThrows(ResponseStatusException.class,()->access.connect("new",room.id(),guest.token(),""));
        access.disconnect("existing");
        assertThrows(ResponseStatusException.class,()->access.member(guest.token(),"",room.id()));
    }
    @Test void estimatedCapacityDoesNotBecomeAHardRoomLimitAndPairingRemainsUnique() {
        for(int i=0;i<12;i++) {
            var license=repository.addLicense("OwnerKey"+String.format("%02d",i));
            for(int j=0;j<9;j++) repository.create(license.id(),"同名🎵",UUID.randomUUID().toString());
        }
        assertEquals(108,repository.rooms().size());
        var retired=repository.rooms().stream().map(RoomRepository.Room::pairingCode).collect(java.util.stream.Collectors.toSet());
        repository.rotate(System.currentTimeMillis()+600000);
        var codes=repository.rooms().stream().map(RoomRepository.Room::pairingCode).collect(java.util.stream.Collectors.toSet());
        assertEquals(108,codes.size());assertTrue(java.util.Collections.disjoint(retired,codes));
    }
    @Test void simultaneousCreationCannotExceedTheLicenseQuota() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var pending=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<12;i++) pending.add(executor.submit(()->{
                start.await();
                try {repository.create("ROOT","并发🎵",UUID.randomUUID().toString());return true;}
                catch(ResponseStatusException e) {assertEquals(409,e.getStatusCode().value());return false;}
            }));
            start.countDown();int succeeded=0;
            for(var future:pending) if(future.get(5,java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            assertEquals(9,succeeded);assertEquals(9,repository.rooms().size());
        }
    }

    @Test void intervalChangeRotatesEveryRoomInvalidatesAdmissionsButKeepsConnectedMembersAndRestartsClock() throws Exception {
        var first=repository.create("ROOT","周期🎵",UUID.randomUUID().toString());
        var dormant=repository.create("ROOT","休眠🎵",UUID.randomUUID().toString());
        var guest=access.join(first.pairingCode(),"guest");
        access.connect("existing",first.id(),guest.token(),"");
        long before=System.currentTimeMillis();
        repository.saveSystemConfig("{}",1);
        long after=System.currentTimeMillis();
        long next=repository.nextPairingUpdateAt(after);
        assertEquals(1,repository.pairingIntervalMinutes());
        assertTrue(next>=before+60000 && next<=after+60000);
        assertNotEquals(first.pairingCode(),repository.room(first.id()).pairingCode());
        assertNotEquals(dormant.pairingCode(),repository.room(dormant.id()).pairingCode());
        assertThrows(ResponseStatusException.class,()->access.admission(guest.token(),first.id()));
        access.connection("existing");access.member(guest.token(),"",first.id());
        assertThrows(ResponseStatusException.class,()->access.connect("another",first.id(),guest.token(),""));
        var fresh=access.join(repository.room(first.id()).pairingCode(),"fresh");
        assertEquals(next,fresh.expiresAt());
        var saved=repository.rooms();repository.close();
        repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        assertEquals(1,repository.pairingIntervalMinutes());
        assertEquals(next,repository.nextPairingUpdateAt(System.currentTimeMillis()));
        assertEquals(saved,repository.rooms());
        repository.rotate(next);
        for(var old:saved) assertNotEquals(old.pairingCode(),repository.room(old.id()).pairingCode());
    }

    @Test void publicPairingDefaultsClosedAndItsVisibilitySettingSurvivesRestart() throws Exception {
        var room=repository.create("ROOT","展示🎵",UUID.randomUUID().toString());
        assertFalse(room.pairingOpen());
        repository.setPairingOpen(room.id(),true);
        repository.close();repository=new RoomRepository(config,new ObjectMapper());repository.initialize();
        assertTrue(repository.room(room.id()).pairingOpen());
        repository.setPairingOpen(room.id(),false);
        assertFalse(repository.room(room.id()).pairingOpen());
    }

    @Test void managerCommandsUseConnectionIdentityAndRecheckLicenseOwnership() {
        var license=repository.addLicense("OwnerKey9");
        var room=repository.create(license.id(),"权限🎵",UUID.randomUUID().toString());
        var guest=access.join(room.pairingCode(),"guest");
        var owner=access.login("OwnerKey9","owner");
        var root=access.login("TestRoot9","root");
        access.connect("user",room.id(),guest.token(),"");
        access.connect("owner",room.id(),"owner-profile",owner.token());
        access.connect("root",room.id(),"root-profile",root.token());
        var support=new org.thornex.musicparty.service.command.CommandSupport(access,
                org.mockito.Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        try(var ignored=RoomContext.enter(room.id())) {
            assertFalse(support.requireManager(new org.thornex.musicparty.dto.User("guest-profile","user","User")));
            // Logging in for //admin does not alter a User connection's permissions.
            access.login("OwnerKey9","user-admin");
            assertFalse(support.requireManager(new org.thornex.musicparty.dto.User("guest-profile","user","User")));
            assertTrue(support.requireManager(new org.thornex.musicparty.dto.User("owner-profile","owner","Owner")));
            assertTrue(support.requireManager(new org.thornex.musicparty.dto.User("root-profile","root","Root")));
            repository.replaceLicense(license.id(),"NewOwner9");
            assertFalse(support.requireManager(new org.thornex.musicparty.dto.User("owner-profile","owner","Owner")));
        }
    }
}
