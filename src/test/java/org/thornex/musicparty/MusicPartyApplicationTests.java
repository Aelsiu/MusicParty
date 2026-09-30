package org.thornex.musicparty;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties={"app.rooms.root-key=TestRoot9", "app.rooms.database=target/test-context.sqlite", "app.rooms.license-file=target/test-context-licenses.json"})
class MusicPartyApplicationTests {

	@Test
	void contextLoads() {
	}

}
