package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class FfmpegService {

    @Value("${app.ffmpeg.path:}")
    private String ffmpegPath;

    private static final String DOWNLOAD_URL = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    @PostConstruct
    private void initializePath() {
        if (ffmpegPath == null || ffmpegPath.isEmpty()) {
            // Use system command which should be in PATH
            ffmpegPath = "ffmpeg";
        } else if (!Paths.get(ffmpegPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            ffmpegPath = Paths.get(ffmpegPath).toAbsolutePath().toString();
        }
        
        log.info("[FFMPEG] Initialized path: {}", ffmpegPath);
    }

    public void ensureAvailable() throws IOException {
        // Path is configured via environment variables or defaults
        // FFmpeg is installed in Docker, no need to download
        log.info("[FFMPEG] Using configured path: {}", ffmpegPath);
    }

    private void download() throws IOException {
        Path target = Paths.get(ffmpegPath);
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);

        Path tempZip = Files.createTempFile("ffmpeg", ".zip");

        try {
            // Скачиваем ZIP
            try (var in = java.net.URI.create(DOWNLOAD_URL).toURL().openStream()) {
                Files.copy(in, tempZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("[FFMPEG] Извлечение...");
            // Извлекаем ffmpeg.exe из архива
            boolean found = false;
            try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(tempZip.toFile()))) {
                ZipEntry zipEntry = zis.getNextEntry();
                while (zipEntry != null) {
                    if (zipEntry.getName().endsWith("bin/ffmpeg.exe")) {
                        Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        found = true;
                        break;
                    }
                    zipEntry = zis.getNextEntry();
                }
            }

            if (!found) {
                throw new IOException("ffmpeg.exe не найден в архиве");
            }

            target.toFile().setExecutable(true);
            log.info("[FFMPEG] Скачано: {}", ffmpegPath);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}
