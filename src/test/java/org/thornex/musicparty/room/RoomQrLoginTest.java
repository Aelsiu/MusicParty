package org.thornex.musicparty.room;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.service.*;
import org.thornex.musicparty.service.api.NeteaseMusicApiService;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomQrLoginTest {
    private HttpServer server;
    private ExecutorService executor;
    private RoomQrLoginService login;
    private NeteaseMusicApiService netease;
    private QueuePersistenceService persistence;
    private RoomAccessService access;
    private final AtomicInteger code=new AtomicInteger(801);
    private CountDownLatch arrived, respond;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String result;
            if(path.endsWith("/key")) result="{\"data\":{\"unikey\":\"fixture-key\"}}";
            else if(path.endsWith("/create")) result="{\"data\":{\"qrimg\":\"data:image/png;base64,fixture\"}}";
            else {
                if(arrived!=null) {arrived.countDown();try {respond.await(5,TimeUnit.SECONDS);} catch(InterruptedException e) {Thread.currentThread().interrupt();}}
                result="{\"code\":"+code.get()+",\"cookie\":\"MUSIC_U=fixture-only\"}";
            }
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);try(var stream=exchange.getResponseBody()) {stream.write(bytes);}
        });server.start();
        var config=new AppProperties();config.getNetease().setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        access=mock(RoomAccessService.class);when(access.token()).thenReturn("opaque-task");
        netease=mock(NeteaseMusicApiService.class);when(netease.checkCookie(anyString())).thenReturn(Mono.just(true));
        persistence=mock(QueuePersistenceService.class);
        login=new RoomQrLoginService(WebClient.create(),config,access,netease,mock(MusicPlayerService.class),persistence);
    }
    @AfterEach void cleanup() {server.stop(0);executor.shutdownNow();}
    private String create() {return RoomContext.call("QrTest01",()->(String)login.create("manager").get("task"));}
    @Test void waitingConfirmationAndSuccessSaveValidatedCookieOnlyOnce() {
        String task=create();
        assertEquals("WAITING",RoomContext.call("QrTest01",()->login.check(task,"manager").get("state")));
        code.set(802);assertEquals("CONFIRMING",RoomContext.call("QrTest01",()->login.check(task,"manager").get("state")));
        code.set(803);assertEquals("SUCCESS",RoomContext.call("QrTest01",()->login.check(task,"manager").get("state")));
        RoomContext.call("QrTest01",()->login.check(task,"manager"));
        verify(netease,times(1)).updateCookie("MUSIC_U=fixture-only");verify(persistence,times(1)).saveNow();
    }
    @Test void invalidCookieDoesNotReplaceThePreviousCredential() {
        when(netease.checkCookie(anyString())).thenReturn(Mono.just(false));String task=create();code.set(803);
        assertEquals("FAILED",RoomContext.call("QrTest01",()->login.check(task,"manager").get("state")));
        verify(netease,never()).updateCookie(anyString());verify(persistence,never()).saveNow();
    }
    @Test void cancellationDuringNetworkRequestPreventsLateCookieSave() throws Exception {
        String task=create();code.set(803);arrived=new CountDownLatch(1);respond=new CountDownLatch(1);
        var pending=executor.submit(()->RoomContext.call("QrTest01",()->login.check(task,"manager")));
        assertTrue(arrived.await(5,TimeUnit.SECONDS));login.cancel(task,"manager");respond.countDown();
        assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS)).getCause());
        verify(netease,never()).updateCookie(anyString());
    }
    @Test void revokedManagementSessionCannotSaveAnInFlightLoginResult() throws Exception {
        String task=create();code.set(803);arrived=new CountDownLatch(1);respond=new CountDownLatch(1);
        var pending=executor.submit(()->RoomContext.call("QrTest01",()->login.check(task,"manager")));
        assertTrue(arrived.await(5,TimeUnit.SECONDS));when(access.own("manager","QrTest01")).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));respond.countDown();
        assertInstanceOf(ResponseStatusException.class,assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS)).getCause());
        verify(netease,never()).updateCookie(anyString());
    }
    @Test void closingAnOldTabDoesNotCancelTheNewLoginTask() {
        when(access.token()).thenReturn("old-task","new-task");
        String oldTask=create();String newTask=create();
        login.cancel(oldTask,"manager");
        assertEquals("WAITING",RoomContext.call("QrTest01",()->login.check(newTask,"manager").get("state")));
        assertThrows(ResponseStatusException.class,()->RoomContext.call("QrTest01",()->login.check(oldTask,"manager")));
    }
}
