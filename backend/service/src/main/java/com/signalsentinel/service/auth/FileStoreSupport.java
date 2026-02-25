package com.signalsentinel.service.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class FileStoreSupport {
    private FileStoreSupport() {
    }

    static <T> List<T> readList(Path path, TypeReference<List<T>> type) {
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (InputStream in = Files.newInputStream(path)) {
            return new ArrayList<>(JsonUtils.objectMapper().readValue(in, type));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read file store " + path, e);
        }
    }

    static void writeListAtomically(Path path, List<?> values) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            JsonUtils.objectMapper().writeValue(tmp.toFile(), values);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write file store " + path, e);
        }
    }
}
