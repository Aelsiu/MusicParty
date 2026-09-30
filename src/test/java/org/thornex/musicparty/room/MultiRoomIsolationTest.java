package org.thornex.musicparty.room;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.dto.*;
import org.thornex.musicparty.enums.*;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class MultiRoomIsolationTest {
    private static final Path DIRECTORY=Path.of("target","isolation-"+UUID.randomUUID());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.rooms.root-key",()->"TestRoot9");r.add("app.rooms.database",()->DIRECTORY.resolve("rooms.sqlite").toString());r.add("app.rooms.license-file",()->DIRECTORY.resolve("licenses.json").toString());r.add("app.music-api.ffmpeg-path",()->"unavailable-test-ffmpeg");
    }
    @Autowired RoomRepository repository;
    @Autowired RoomScope scope;
    @Autowired ApplicationContext context;
    @Autowired RoomAccessService access;
    @Autowired RoomLifecycleService lifecycle;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Test void deletingOneRoomDoesNotBreakAnotherRoomsHttpRequests() throws Exception {
        var first=repository.create("ROOT","请求甲",UUID.randomUUID().toString());
        var second=repository.create("ROOT","请求乙",UUID.randomUUID().toString());
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        com.sun.net.httpserver.HttpHandler handler=exchange->{
            byte[] body="{\"result\":{\"userprofiles\":[{\"userId\":1,\"nickname\":\"test\"}]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()) {output.write(body);}
        };server.createContext("/search",handler);server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            try(var ignored=RoomContext.enter(first.id())) {
                var config=context.getBean(AppProperties.class);
                config.getNetease().setBaseUrl(url);config.getNetease().setCookie("fixture");
                assertEquals(1,context.getBean(org.thornex.musicparty.service.api.NeteaseMusicApiService.class)
                        .searchUsers("first").block(java.time.Duration.ofSeconds(5)).size());
            }
            repository.deleteRoom(first.id());lifecycle.deleted(first.id());
            int port=server.getAddress().getPort();server.stop(0);
            server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",port),0);
            server.setExecutor(executor);server.createContext("/search",handler);server.start();
            Thread.sleep(200); // The closed keep-alive connection must be observed before the next room acquires one.
            reactor.core.publisher.Mono<java.util.List<UserSearchResult>> request;
            try(var ignored=RoomContext.enter(second.id())) {
                var config=context.getBean(AppProperties.class);
                config.getNetease().setBaseUrl(url);config.getNetease().setCookie("fixture");
                request=context.getBean(org.thornex.musicparty.service.api.NeteaseMusicApiService.class).searchUsers("second")
                        .map(users->{assertEquals(second.id(),RoomContext.require());return users;});
            }
            assertEquals(1,request.block(java.time.Duration.ofSeconds(5)).size());
        } finally {
            server.stop(0);executor.shutdownNow();
            for(String id:java.util.List.of(first.id(),second.id())) if(repository.exists(id)) {repository.deleteRoom(id);lifecycle.deleted(id);}
        }
    }
    @Test void deletingRoomCancelsAnInFlightDownloadAndRemovesItsPartialCache() throws Exception {
        var room=repository.create("ROOT","下载🎵",UUID.randomUUID().toString());
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/audio",exchange->{
            try {
                exchange.sendResponseHeaders(200,0);
                try(var output=exchange.getResponseBody()) {
                    output.write(new byte[65536]);output.flush();started.countDown();
                    finish.await(5,java.util.concurrent.TimeUnit.SECONDS);output.write(new byte[65536]);
                }
            } catch(java.io.IOException ignored) { /* Room deletion closes the HTTP download. */ }
            catch(InterruptedException e) {Thread.currentThread().interrupt();}
        });server.start();
        java.nio.file.Path cache;
        try {
            try(var ignored=RoomContext.enter(room.id())) {
                var downloads=context.getBean(LocalCacheService.class);
                cache=java.nio.file.Path.of(downloads.getCacheDirectory());
                downloads.submitDownload("in-flight",reactor.core.publisher.Mono.just(new LocalCacheService.DownloadSource("http://127.0.0.1:"+server.getAddress().getPort()+"/audio",".wav")),java.util.Map.of());
            }
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            repository.deleteRoom(room.id());lifecycle.deleted(room.id());finish.countDown();
            assertFalse(java.nio.file.Files.exists(cache));assertFalse(scope.active(room.id()));
        } finally {
            finish.countDown();server.stop(0);executor.shutdownNow();
            if(repository.exists(room.id())) {repository.deleteRoom(room.id());lifecycle.deleted(room.id());}
        }
    }
    @Test void lateAsyncResponseAfterDeletionReleasesResourcesWithoutDeliveringIntoTheRoom() {
        var room=repository.create("ROOT","异步🎵",UUID.randomUUID().toString());
        var result=reactor.core.publisher.Sinks.<String>one();
        var released=new java.util.concurrent.atomic.AtomicInteger();
        var received=new java.util.ArrayList<String>();
        try(var ignored=RoomContext.enter(room.id())) {
            context.getBean(MusicQueueManager.class).getQueueSnapshot();
            reactor.core.publisher.Mono.using(Object::new,resource->result.asMono(),resource->released.incrementAndGet()).subscribe(received::add);
        }
        repository.deleteRoom(room.id());lifecycle.deleted(room.id());
        result.tryEmitValue("late-response");
        assertEquals(1,released.get());assertTrue(received.isEmpty());
    }
    @Test void stateAndConfigurationAreIsolatedAndSnapshotsRestoreOnlyTheirRoom() {
        var a=repository.create("ROOT","同名🎵",UUID.randomUUID().toString());var b=repository.create("ROOT","同名🎵",UUID.randomUUID().toString());
        try(var ignored=RoomContext.enter(a.id())) {
            context.getBean(QueuePersistenceService.class).ensureLoaded();
            var config=context.getBean(AppProperties.class);config.getQueue().setMaxSize(17);config.getNetease().setCookie("room-a-private-cookie");
            context.getBean(MusicQueueManager.class).add(new Music("songA","房间 A 歌曲",java.util.List.of("歌手"),10000,"netease",""),new UserSummary("userA","sessionA","甲",false),QueueItemStatus.READY);
            var user=context.getBean(UserService.class);user.handleConnect("sessionA","shared-user","用户甲");user.handleConnect("second-tab","shared-user","用户甲");user.disconnectUser("second-tab");assertEquals(1,user.getOnlineUserSummaries().size());
            context.getBean(QueuePersistenceService.class).saveNow();
            assertTrue(context.getBean(MusicPlayerService.class).getCurrentPlayerState().isPaused());
        }
        try(var ignored=RoomContext.enter(b.id())) {
            context.getBean(QueuePersistenceService.class).ensureLoaded();
            assertTrue(context.getBean(MusicQueueManager.class).getQueueSnapshot().isEmpty());
            assertEquals(1000,context.getBean(AppProperties.class).getQueue().getMaxSize());assertEquals("",context.getBean(AppProperties.class).getNetease().getCookie());
            context.getBean(UserService.class).handleConnect("sessionB","shared-user","用户甲");assertEquals(1,context.getBean(UserService.class).getOnlineUserSummaries().size());
        }
        assertTrue(repository.payload(a.id(),"payload").contains("songA"));assertFalse(repository.payload(a.id(),"payload").contains("room-a-private-cookie"));
        assertTrue(repository.payload(a.id(),"config").contains("room-a-private-cookie"));
        repository.deleteRoom(a.id());scope.destroy(a.id());assertFalse(repository.exists(a.id()));
        try(var ignored=RoomContext.enter(a.id())) { assertThrows(RuntimeException.class,()->context.getBean(MusicQueueManager.class).getQueueSnapshot()); }
        try(var ignored=RoomContext.enter(b.id())) {assertEquals(1,context.getBean(UserService.class).getOnlineUserSummaries().size());}
    }
    @Test void briefReconnectKeepsPlaybackAndAnEmptyRoomPausesBeforeRejoining() {
        var room=repository.create("ROOT","重连🎵",UUID.randomUUID().toString());
        var manager=access.login("TestRoot9","reconnect-test");
        access.connect("first",room.id(),"",manager.token());lifecycle.joined(room.id());
        try(var ignored=RoomContext.enter(room.id())) {
            var player=context.getBean(MusicPlayerService.class);
            Object target=((org.springframework.aop.scope.ScopedObject)player).getTargetObject();
            var paused=(java.util.concurrent.atomic.AtomicBoolean)org.springframework.test.util.ReflectionTestUtils.getField(target,"isPaused");
            paused.set(false);
            access.disconnect("first");lifecycle.departed(room.id());lifecycle.tick();assertFalse(player.getCurrentPlayerState().isPaused());
            access.connect("reconnected",room.id(),"",manager.token());lifecycle.joined(room.id());assertFalse(player.getCurrentPlayerState().isPaused());
            access.disconnect("reconnected");lifecycle.departed(room.id());
            @SuppressWarnings("unchecked")
            var empty=(java.util.Map<String,Long>)org.springframework.test.util.ReflectionTestUtils.getField(lifecycle,"emptySince");
            empty.put(room.id(),System.currentTimeMillis()-11000);lifecycle.tick();assertTrue(player.getCurrentPlayerState().isPaused());
            access.connect("later",room.id(),"",manager.token());lifecycle.joined(room.id());assertTrue(player.getCurrentPlayerState().isPaused());
            access.disconnect("later");lifecycle.departed(room.id());
        }
        repository.deleteRoom(room.id());lifecycle.deleted(room.id());
    }
    @Test void guestCannotReachManagementThroughCanonicalEncodedOrMatrixPaths() throws Exception {
        var room=repository.create("ROOT","权限🎵",UUID.randomUUID().toString());
        var guest=access.join(room.pairingCode(),"http-guest");
        for(String path:java.util.List.of("/api/admin/config/cookie","/api/%61dmin/config/cookie","/api/admin;param/config/cookie")) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(java.net.URI.create(path))
                    .header("X-Room-ID",room.id()).header("X-Room-Token",guest.token())
                    .contentType("application/json").content("{\"platform\":\"netease\",\"value\":\"never-saved\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        assertNull(repository.payload(room.id(),"config"));repository.deleteRoom(room.id());lifecycle.deleted(room.id());
    }
}
