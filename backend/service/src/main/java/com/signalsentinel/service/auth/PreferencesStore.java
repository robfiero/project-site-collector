package com.signalsentinel.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PreferencesStore {
    private static final TypeReference<List<UserPreferences>> TYPE = new TypeReference<>() {
    };

    private final Path path;
    private final Object lock = new Object();

    public PreferencesStore(Path path) {
        this.path = path;
    }

    public UserPreferences getForUser(String userId) {
        synchronized (lock) {
            Optional<UserPreferences> value = load().stream()
                    .filter(pref -> pref.userId().equals(userId))
                    .findFirst();
            return value.orElseGet(() -> UserPreferences.empty(userId));
        }
    }

    public UserPreferences putForUser(UserPreferences preferences) {
        synchronized (lock) {
            List<UserPreferences> list = load();
            int existing = indexOf(list, preferences.userId());
            if (existing >= 0) {
                list.set(existing, preferences);
            } else {
                list.add(preferences);
            }
            FileStoreSupport.writeListAtomically(path, list);
            return preferences;
        }
    }

    public List<UserPreferences> all() {
        synchronized (lock) {
            return List.copyOf(load());
        }
    }

    public boolean deleteForUser(String userId) {
        synchronized (lock) {
            List<UserPreferences> list = load();
            int existing = indexOf(list, userId);
            if (existing < 0) {
                return false;
            }
            list.remove(existing);
            FileStoreSupport.writeListAtomically(path, list);
            return true;
        }
    }

    private int indexOf(List<UserPreferences> list, String userId) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).userId().equals(userId)) {
                return i;
            }
        }
        return -1;
    }

    private List<UserPreferences> load() {
        return FileStoreSupport.readList(path, TYPE);
    }
}
