package org.thornex.musicparty.playback;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.VideoFavorites.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoFavoritesRegistryTest {
    final Instant now=Instant.parse("2026-10-08T00:00:00Z");
    final VideoImportRegistry.Owner owner=new VideoImportRegistry.Owner("r","e","browser","member");
    final List<Selection> selection=List.of(new Selection("1",2));
    final List<Selected> selected=List.of(new Selected("1","BV1GJ411x7h7",2));
    @Test void immutablePreviewOwnershipExpiryAndSelection() {
        var registry=new VideoPreviewRegistry(); var context=new VideoPreviewRegistry.Context("r","e",1,"browser");
        String id=registry.create(context,now);
        registry.put(id,context,new Page("123","Folder",1,List.of(new Item("1","BV1GJ411x7h7","Title","","Owner",0,true,null)),true),now);
        assertEquals(selected,registry.select(id,context,selection,now));
        assertThrows(Failure.class,()->registry.select(id,new VideoPreviewRegistry.Context("r","e",1,"other"),selection,now));
        assertThrows(Failure.class,()->registry.select(id,context,List.of(new Selection("1",2),new Selection("1",3)),now));
        assertEquals("PREVIEW_EXPIRED",assertThrows(Failure.class,()->registry.select(id,context,selection,now.plusSeconds(600))).getCode());
    }
    @Test void reconnectIdempotencyConflictAndTerminalExpiry() {
        var registry=new VideoImportRegistry(); var key=new VideoImportRegistry.Key(owner,"request"); var args=new VideoImportRegistry.Parameters("preview",selection);
        var accepted=registry.reserve(key,args,"session",1,selected,now);
        assertSame(accepted.entry(),registry.reserve(key,args,"new-session",1,selected,now).entry());
        assertThrows(Failure.class,()->registry.existing(key,new VideoImportRegistry.Parameters("different",selection),now));
        registry.resolving(accepted.entry());
        var result=registry.finish(accepted.entry(),List.of(new ItemResult("1","BV1GJ411x7h7:p2","ADDED",null,false,"queue")),now.plusSeconds(40));
        assertEquals("COMPLETED",result.status()); assertEquals(1,result.counts().added());
        assertEquals(result,registry.status(owner,result.operationId(),null,now.plusSeconds(600)));
        assertThrows(Failure.class,()->registry.status(new VideoImportRegistry.Owner("r","e","other","member"),result.operationId(),null,now));
        assertEquals("IMPORT_RESULT_UNAVAILABLE",assertThrows(Failure.class,()->registry.status(owner,result.operationId(),null,now.plusSeconds(640))).getCode());
    }
    @Test void cancellationCannotResurrectAndAllSlotsRetainedUntilExpiry() {
        var registry=new VideoImportRegistry();
        for(int i=0;i<32;i++) {
            var key=new VideoImportRegistry.Key(owner,"request"+i);
            var entry=registry.reserve(key,new VideoImportRegistry.Parameters("preview",selection),"session",1,selected,now).entry();
            registry.cancel(e->e==entry,now);
            assertEquals("CANCELLED",registry.finish(entry,List.of(new ItemResult("1",null,"ADDED",null,false,"q")),now).status());
        }
        assertEquals("IMPORT_BUSY",assertThrows(Failure.class,()->registry.reserve(new VideoImportRegistry.Key(owner,"overflow"),new VideoImportRegistry.Parameters("preview",selection),"session",1,selected,now)).getCode());
        assertFalse(registry.reserve(new VideoImportRegistry.Key(owner,"new"),new VideoImportRegistry.Parameters("preview",selection),"session",1,selected,now.plusSeconds(600)).existing());
    }
    @Test void onlyOriginatingSessionIsCancelled() {
        var registry=new VideoImportRegistry(); var entry=registry.reserve(new VideoImportRegistry.Key(owner,"r"),new VideoImportRegistry.Parameters("p",selection),"origin",1,selected,now).entry();
        assertTrue(registry.cancel(e->e.sessionId().equals("other"),now).isEmpty());
        assertEquals(1,registry.cancel(e->e.sessionId().equals("origin"),now).size());
        assertEquals(1,entry.operation().counts().cancelled()); assertEquals(0,entry.operation().counts().pending());
    }
}
