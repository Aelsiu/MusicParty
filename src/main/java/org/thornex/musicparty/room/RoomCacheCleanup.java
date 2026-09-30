package org.thornex.musicparty.room;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Slf4j
final class RoomCacheCleanup {
    private RoomCacheCleanup() {}
    static void delete(String id) {
        if(id==null || !id.matches("[A-Za-z0-9]{8}")) return;
        Path parent=Path.of("cached_media","rooms").toAbsolutePath().normalize();
        Path target=parent.resolve(id).normalize();
        if(!target.getParent().equals(parent) || !Files.exists(target)) return;
        try(var files=Files.walk(target)) {
            files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException e){log.warn("Room cache cleanup failed: {}",id);}});
        } catch(IOException e) {log.warn("Room cache cleanup failed: {}",id);}
    }
}
