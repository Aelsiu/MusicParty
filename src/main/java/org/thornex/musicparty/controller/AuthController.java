package org.thornex.musicparty.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.room.*;
import java.util.Map;

@RestController
public class AuthController {
    private final RoomRepository repository;
    private final AppProperties defaults;
    public AuthController(RoomRepository repository,@Qualifier("defaultAppProperties") AppProperties defaults) { this.repository=repository;this.defaults=defaults; }
    @GetMapping("/api/auth/status") public Map<String,Object> getStatus() { return Map.of("isSetup",!repository.rooms().isEmpty(),"roomCount",repository.rooms().size()); }
    @GetMapping("/api/config") public Map<String,String> getConfig() { return Map.of("authorName",defaults.getAuthorName(),"backWords",defaults.getBackWords()); }
    public static boolean isValidPin(String value) { return value!=null && value.matches("[0-9]{4}"); }
    public String getRawPassword() { return repository.room(RoomContext.require()).pairingCode(); }
    public String getRoomName() { return repository.room(RoomContext.require()).name(); }
    // Directory data must never be restored from a playback snapshot.
    public void restoreRoom(String name,String password) {}
}
