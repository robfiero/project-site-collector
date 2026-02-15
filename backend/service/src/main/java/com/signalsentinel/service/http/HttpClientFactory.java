package com.signalsentinel.service.http;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class HttpClientFactory {
    private HttpClientFactory() {
    }

    public static HttpClient create(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        SSLContext sslContext = sslContextFromEnvironment(System.getenv());
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    static HttpClient create(Duration connectTimeout, Map<String, String> environment) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        SSLContext sslContext = sslContextFromEnvironment(environment);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    private static SSLContext sslContextFromEnvironment(Map<String, String> environment) {
        String truststorePath = environment.get("TRUSTSTORE_PATH");
        if (truststorePath == null || truststorePath.isBlank()) {
            return null;
        }

        String truststorePassword = environment.get("TRUSTSTORE_PASSWORD");
        if (truststorePassword == null) {
            throw new IllegalStateException("TRUSTSTORE_PASSWORD must be set when TRUSTSTORE_PATH is configured");
        }

        Path path = Path.of(truststorePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("Truststore file does not exist: " + path);
        }

        String type = inferTruststoreType(path);
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore trustStore = KeyStore.getInstance(type);
            trustStore.load(in, truststorePassword.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build SSL context from truststore " + path, e);
        }
    }

    private static String inferTruststoreType(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".pkcs12")) {
            return "PKCS12";
        }
        return "JKS";
    }
}
