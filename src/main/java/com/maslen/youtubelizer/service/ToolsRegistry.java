package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ToolsRegistry {
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public void markInitialized() {
        initialized.set(true);
        log.info("External tools marked as initialized");
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
