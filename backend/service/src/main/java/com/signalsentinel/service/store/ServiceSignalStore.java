package com.signalsentinel.service.store;

import com.signalsentinel.collectors.api.SignalStore;

import java.util.Map;

public interface ServiceSignalStore extends SignalStore {
    Map<String, Object> getAllSignals();
}
