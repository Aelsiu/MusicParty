package org.thornex.musicparty.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.thornex.musicparty.config.AppProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthControllerPinTest {
    @Test
    void creationRequiresNameAndFourDigitsAndJoinOnlyAcceptsPin() {
        AppProperties properties = new AppProperties();
        properties.setAdminPassword("secret-admin");
        AuthController auth = new AuthController(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertEquals(400, auth.setupPassword(Map.of("roomName", "房间", "password", "abcd")).getStatusCode().value());
        assertEquals(400, auth.setupPassword(Map.of("roomName", " ", "password", "1234")).getStatusCode().value());
        assertEquals(200, auth.setupPassword(Map.of("roomName", " 房间 ", "password", "1234")).getStatusCode().value());
        assertEquals("房间", auth.getStatus().getBody().get("roomName"));
        assertTrue((Boolean) auth.getStatus().getBody().get("isSetup"));
        assertEquals(200, auth.verifyPassword(Map.of("password", "1234"), request).getStatusCode().value());
        assertEquals(401, auth.verifyPassword(Map.of("password", "secret-admin"), request).getStatusCode().value());
        assertFalse(AuthController.isValidPin("12345"));
    }
}
