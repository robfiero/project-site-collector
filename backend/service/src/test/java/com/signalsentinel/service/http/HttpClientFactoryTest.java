package com.signalsentinel.service.http;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpClientFactoryTest {
    @Test
    void createsDefaultClientWhenTruststorePathNotProvided() {
        HttpClient client = HttpClientFactory.create(Duration.ofMillis(200), Map.of());
        assertNotNull(client);
    }

    @Test
    void failsWhenTruststoreFileIsMissing() {
        Map<String, String> env = Map.of(
                "TRUSTSTORE_PATH", "/tmp/does-not-exist.jks",
                "TRUSTSTORE_PASSWORD", "changeit"
        );

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> HttpClientFactory.create(Duration.ofMillis(200), env)
        );
        assertTrue(ex.getMessage().contains("Truststore file does not exist"));
    }

    @Test
    void createsClientWithJksTruststore() throws Exception {
        Path truststore = Files.createTempFile("truststore-", ".jks");
        writeEmptyTruststore(truststore, "JKS", "changeit".toCharArray());

        HttpClient client = HttpClientFactory.create(Duration.ofMillis(200), Map.of(
                "TRUSTSTORE_PATH", truststore.toString(),
                "TRUSTSTORE_PASSWORD", "changeit"
        ));

        assertNotNull(client);
    }

    @Test
    void createsClientWithPkcs12Truststore() throws Exception {
        Path truststore = Files.createTempFile("truststore-", ".p12");
        writeEmptyTruststore(truststore, "PKCS12", "changeit".toCharArray());

        HttpClient client = HttpClientFactory.create(Duration.ofMillis(200), Map.of(
                "TRUSTSTORE_PATH", truststore.toString(),
                "TRUSTSTORE_PASSWORD", "changeit"
        ));

        assertNotNull(client);
    }

    @Test
    void failsWithWrongTruststorePassword() throws Exception {
        Path truststore = Files.createTempFile("truststore-", ".jks");
        writeEmptyTruststore(truststore, "JKS", "correct-password".toCharArray());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> HttpClientFactory.create(Duration.ofMillis(200), Map.of(
                        "TRUSTSTORE_PATH", truststore.toString(),
                        "TRUSTSTORE_PASSWORD", "wrong-password"
                ))
        );
        assertTrue(ex.getMessage().contains("Failed to build SSL context from truststore"));
    }

    private static void writeEmptyTruststore(Path file, String type, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, password);
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, password);
        }
    }
}
