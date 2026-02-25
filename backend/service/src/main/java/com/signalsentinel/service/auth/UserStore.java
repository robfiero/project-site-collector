package com.signalsentinel.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class UserStore {
    private static final TypeReference<List<AuthUser>> TYPE = new TypeReference<>() {
    };

    private final Path path;
    private final Object lock = new Object();

    public UserStore(Path path) {
        this.path = path;
    }

    public Optional<AuthUser> findByEmail(String email) {
        synchronized (lock) {
            return load().stream()
                    .filter(user -> user.email().equalsIgnoreCase(email))
                    .findFirst();
        }
    }

    public Optional<AuthUser> findById(String id) {
        synchronized (lock) {
            return load().stream()
                    .filter(user -> user.id().equals(id))
                    .findFirst();
        }
    }

    public AuthUser save(AuthUser user) {
        synchronized (lock) {
            List<AuthUser> users = load();
            int index = indexOf(users, user.id());
            if (index >= 0) {
                users.set(index, user);
            } else {
                users.add(user);
            }
            FileStoreSupport.writeListAtomically(path, users);
            return user;
        }
    }

    public List<AuthUser> all() {
        synchronized (lock) {
            return List.copyOf(load());
        }
    }

    private int indexOf(List<AuthUser> users, String id) {
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private List<AuthUser> load() {
        return FileStoreSupport.readList(path, TYPE);
    }
}
