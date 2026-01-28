package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class FfmpegService {

    @Value("${app.ffmpeg.path:./ffmpeg.exe}")
    private String ffmpegPath;

    private static final String DOWNLOAD_URL = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    public void ensureAvailable() throws IOException {
        Path path = Paths.get(ffmpegPath);
        if (Files.notExists(path)) {
            log.info("[FFMPEG] Скачивание FFmpeg...");
            download();
        } else {
            log.info("[FFMPEG] Найден: {}", ffmpegPath);
        }
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
