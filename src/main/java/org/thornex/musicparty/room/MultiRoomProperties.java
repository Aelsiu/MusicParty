package org.thornex.musicparty.room;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("app.rooms")
public class MultiRoomProperties {
    private String rootKey;
    private String database = "data/multi-rooms.sqlite";
    private String licenseFile = "config/licenses.json";
    private long managementSessionHours = 12;
    private long reconnectGraceSeconds = 10;
    private int maxConcurrentDownloads = 3;
    private int maxConcurrentTranscoders = 12;
}
