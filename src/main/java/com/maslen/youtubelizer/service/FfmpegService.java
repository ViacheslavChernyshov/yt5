package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class FfmpegService {

    @Value("${app.ffmpeg.path:}")
    private String ffmpegPath;

    @PostConstruct
    private void initializePath() {
        if (ffmpegPath == null || ffmpegPath.isEmpty()) {
            // Use system command which should be in PATH
            ffmpegPath = "ffmpeg";
        } else if (!Paths.get(ffmpegPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            ffmpegPath = Paths.get(ffmpegPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            ffmpegPath = Paths.get(ffmpegPath).normalize().toString();
        }
        
        log.info("[FFMPEG] Initialized path: {}", ffmpegPath);
    }

    public void ensureAvailable() throws IOException {
        // Path is configured via environment variables or defaults
        // FFmpeg is installed in Docker, no need to download
        log.info("[FFMPEG] Using configured path: {}", ffmpegPath);
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}
