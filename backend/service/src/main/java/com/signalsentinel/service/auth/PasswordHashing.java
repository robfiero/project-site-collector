package com.signalsentinel.service.auth;

public interface PasswordHashing {
    String hash(String password);

    boolean verify(String encodedHash, String password);
}
