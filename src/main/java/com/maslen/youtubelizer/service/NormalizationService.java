package com.maslen.youtubelizer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class NormalizationService {

    private final LlamaService llamaService;

    /**
     * Normalizes text using Llama service.
     */
    public String normalizeText(String text, String language) throws IOException, InterruptedException {
        String normalizedText = llamaService.normalizeText(text, language);

        if (normalizedText == null || normalizedText.trim().isEmpty()) {
            return null;
        }

        // Clean and normalize the text (remove excessive newlines/spaces if needed)
        return normalizedText.trim();
    }

    /**
     * Save normalized text to file.
     */
    public void saveNormalizedTextToFile(String videoId, String normalizedText) {
        try {
            Path downloadsDir = Paths.get("downloads");
            Files.createDirectories(downloadsDir);

            Path normalizedFile = downloadsDir.resolve("normalized_" + videoId + ".txt");
            Files.writeString(normalizedFile, normalizedText);
            log.info("Normalized text saved to file: {}", normalizedFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to save normalized text to file for video {}: {}", videoId, e.getMessage());
        }
    }
}
