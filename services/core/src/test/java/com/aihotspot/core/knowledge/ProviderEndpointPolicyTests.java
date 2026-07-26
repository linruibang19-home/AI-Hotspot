package com.aihotspot.core.knowledge;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEndpointPolicyTests {

    @Test
    void userConnectionsRequirePublicHttpsEndpoints() {
        ProviderEndpointPolicy policy = new ProviderEndpointPolicy("development");

        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("http://api.example.com/v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://localhost:8080/v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://127.0.0.1:8080/v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://10.0.0.1/v1", true));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate("https://8.8.8.8/v1?api_key=secret", true));
        assertEquals("https://8.8.8.8/v1", policy.validate("https://8.8.8.8/v1/", true));
    }

    @Test
    void platformConnectionsAllowLocalDevelopmentButNotProduction() {
        assertEquals("http://localhost:8000",
                new ProviderEndpointPolicy("development").validate("http://localhost:8000/", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderEndpointPolicy("production")
                        .validate("http://localhost:8000", false));
    }

    @Test
    void addressClassifierRejectsPrivateAndDocumentationRanges() throws Exception {
        assertFalse(ProviderEndpointPolicy.isPublic(InetAddress.getByName("100.64.0.1")));
        assertFalse(ProviderEndpointPolicy.isPublic(InetAddress.getByName("192.0.2.1")));
        assertFalse(ProviderEndpointPolicy.isPublic(InetAddress.getByName("2001:db8::1")));
        assertTrue(ProviderEndpointPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
    }
}
