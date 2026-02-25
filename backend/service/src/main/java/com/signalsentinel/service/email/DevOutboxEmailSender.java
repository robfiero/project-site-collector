package com.signalsentinel.service.email;

import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class DevOutboxEmailSender implements EmailSender {
    private final List<EmailMessage> outbox = new ArrayList<>();
    private final Path optionalPersistFile;
    private final Object lock = new Object();

    public DevOutboxEmailSender(Path optionalPersistFile) {
        this.optionalPersistFile = optionalPersistFile;
    }

    @Override
    public void send(EmailMessage message) {
        synchronized (lock) {
            outbox.add(message);
            if (optionalPersistFile != null) {
                persist(outbox);
            }
        }
    }

    public List<EmailMessage> recent() {
        synchronized (lock) {
            return List.copyOf(outbox);
        }
    }

    private void persist(List<EmailMessage> messages) {
        try {
            Path parent = optionalPersistFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = optionalPersistFile.resolveSibling(optionalPersistFile.getFileName() + ".tmp");
            JsonUtils.objectMapper().writeValue(tmp.toFile(), messages);
            Files.move(tmp, optionalPersistFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist dev outbox", e);
        }
    }
}
