package com.signalsentinel.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PasswordResetStore {
    private static final TypeReference<List<PasswordResetTokenRecord>> TYPE = new TypeReference<>() {
    };

    private final Path path;
    private final Object lock = new Object();

    public PasswordResetStore(Path path) {
        this.path = path;
    }

    public void save(PasswordResetTokenRecord record) {
        synchronized (lock) {
            List<PasswordResetTokenRecord> values = load();
            values.add(record);
            FileStoreSupport.writeListAtomically(path, values);
        }
    }

    public Optional<PasswordResetTokenRecord> findByHash(String tokenHash) {
        synchronized (lock) {
            return load().stream()
                    .filter(value -> value.tokenHash().equals(tokenHash))
                    .findFirst();
        }
    }

    public void replace(PasswordResetTokenRecord updated) {
        synchronized (lock) {
            List<PasswordResetTokenRecord> values = load();
            for (int i = 0; i < values.size(); i++) {
                PasswordResetTokenRecord existing = values.get(i);
                if (existing.tokenHash().equals(updated.tokenHash())) {
                    values.set(i, updated);
                    FileStoreSupport.writeListAtomically(path, values);
                    return;
                }
            }
            values.add(updated);
            FileStoreSupport.writeListAtomically(path, values);
        }
    }

    private List<PasswordResetTokenRecord> load() {
        return FileStoreSupport.readList(path, TYPE);
    }
}
