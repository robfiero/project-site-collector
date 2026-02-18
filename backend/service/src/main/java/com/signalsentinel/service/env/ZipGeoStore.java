package com.signalsentinel.service.env;

import com.fasterxml.jackson.core.type.TypeReference;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public final class ZipGeoStore {
    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ZipGeoRecord> byZip = new HashMap<>();

    public ZipGeoStore(Path file) {
        this.file = file;
        load();
    }

    public Optional<ZipGeoRecord> get(String zip) {
        lock.lock();
        try {
            return Optional.ofNullable(byZip.get(zip));
        } finally {
            lock.unlock();
        }
    }

    public void put(ZipGeoRecord record) {
        lock.lock();
        try {
            byZip.put(record.zip(), record);
            persist();
        } finally {
            lock.unlock();
        }
    }

    private void load() {
        lock.lock();
        try {
            if (!Files.exists(file)) {
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                List<ZipGeoRecord> records = JsonUtils.objectMapper().readValue(in, new TypeReference<List<ZipGeoRecord>>() {
                });
                for (ZipGeoRecord record : records) {
                    byZip.put(record.zip(), record);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read zip geo store " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            List<ZipGeoRecord> records = new ArrayList<>(byZip.values());
            JsonUtils.objectMapper().writeValue(tmp.toFile(), records);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write zip geo store " + file, e);
        }
    }
}

