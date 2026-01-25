package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class YtDlpService {

    @Value("${app.ytdlp.path:./yt-dlp.exe}")
    private String ytDlpPath;

    private static final String DOWNLOAD_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";

    public void ensureAvailable() throws IOException {
        Path path = Paths.get(ytDlpPath);
        if (Files.notExists(path)) {
            log.info("[YTDLP] Downloading yt-dlp...");
            Files.createDirectories(path.getParent());
            Files.deleteIfExists(path);
            
            try (var in = java.net.URI.create(DOWNLOAD_URL).toURL().openStream()) {
                Files.copy(in, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            
            path.toFile().setExecutable(true);
            log.info("[YTDLP] Downloaded: {}", ytDlpPath);
        } else {
            log.info("[YTDLP] Found: {}", ytDlpPath);
        }
    }

    public String getYtDlpPath() {
        return ytDlpPath;
    }
}
