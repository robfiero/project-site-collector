package com.signalsentinel.service.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

public final class TlsCheckMain {
    private TlsCheckMain() {
    }

    public static void main(String[] args) throws Exception {
        run(args, System.out, System.err);
    }

    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws Exception {
        if (args.length != 1) {
            out.println("Usage: TlsCheckMain <https-url>");
            return 1;
        }

        URI uri;
        try {
            uri = URI.create(args[0]);
        } catch (IllegalArgumentException e) {
            err.println("Invalid URL: " + args[0]);
            return 2;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            err.println("Only https URLs are supported");
            return 2;
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        out.println("Target: " + host + ":" + port);

        X509TrustManager defaultTrustManager = defaultTrustManager();
        RecordingTrustManager recordingTrustManager = new RecordingTrustManager(defaultTrustManager);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{recordingTrustManager}, new SecureRandom());

        boolean handshakeSucceeded = false;
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            handshakeSucceeded = true;
        } catch (SSLHandshakeException expected) {
            // Keep going so we can print chain and validation diagnostics.
        }

        X509Certificate[] chain = recordingTrustManager.lastChain();
        if (chain == null || chain.length == 0) {
            out.println("No certificate chain captured.");
        } else {
            out.println("Presented certificate chain:");
            for (int i = 0; i < chain.length; i++) {
                X509Certificate cert = chain[i];
                out.println("  [" + i + "] Subject: " + cert.getSubjectX500Principal().getName());
                out.println("      Issuer : " + cert.getIssuerX500Principal().getName());
            }
        }

        boolean chainValid = false;
        String chainValidationError = null;
        if (chain != null && chain.length > 0) {
            try {
                String authType = chain[0].getPublicKey().getAlgorithm();
                defaultTrustManager.checkServerTrusted(chain, authType);
                chainValid = true;
            } catch (CertificateException e) {
                chainValidationError = e.getMessage();
            }
        }

        out.println("Handshake succeeded: " + handshakeSucceeded);
        out.println("Chain validates against default JVM truststore: " + chainValid);
        if (!chainValid && chainValidationError != null) {
            out.println("Validation error: " + chainValidationError);
        }
        return 0;
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        return Arrays.stream(tmf.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No default X509TrustManager found"));
    }

    private static final class RecordingTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;
        private volatile X509Certificate[] lastChain;

        private RecordingTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        X509Certificate[] lastChain() {
            return lastChain;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            lastChain = chain;
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            lastChain = chain;
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            lastChain = chain;
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
