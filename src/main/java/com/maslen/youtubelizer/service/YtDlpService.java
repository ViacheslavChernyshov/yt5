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
public class YtDlpService {

    @Value("${app.ytdlp.path:}")
    private String ytDlpPath;

    @PostConstruct
    private void initializePath() {
        if (ytDlpPath == null || ytDlpPath.isEmpty()) {
            // Use system command which should be in PATH
            ytDlpPath = "yt-dlp";
        } else if (!Paths.get(ytDlpPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            ytDlpPath = Paths.get(ytDlpPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            ytDlpPath = Paths.get(ytDlpPath).normalize().toString();
        }
        
        log.info("[YTDLP] Initialized path: {}", ytDlpPath);
    }

    public void ensureAvailable() throws IOException {
        // Path is configured via environment variables or defaults
        // No need to download in Docker environment
        log.info("[YTDLP] Using configured path: {}", ytDlpPath);
    }

    public java.io.File downloadVideo(String url, Path outputDir, String fileNameWithoutExt)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDir);
        String outputPath = outputDir.resolve(fileNameWithoutExt + ".%(ext)s").toString();

        String[] command = {
                ytDlpPath,
                "--extractor-args", "youtube:player_client=android",
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/mp4",
                "-o", outputPath,
                "--no-warnings",
                url
        };

        executeCommand(command);

        // Поиск скачанного файла
        try (java.util.stream.Stream<Path> stream = Files.list(outputDir)) {
            return stream
                    .filter(file -> file.getFileName().toString().startsWith(fileNameWithoutExt))
                    .findFirst()
                    .map(Path::toFile)
                    .orElseThrow(() -> new IOException("Скачанный видео файл не найден для: " + fileNameWithoutExt));
        }
    }

    public java.io.File downloadAudio(String url, Path outputDir, String fileNameWithoutExt)
            throws IOException, InterruptedException {
        Files.createDirectories(outputDir);
        String outputPath = outputDir.resolve(fileNameWithoutExt + ".%(ext)s").toString();

        String[] command = {
                ytDlpPath,
                "--extractor-args", "youtube:player_client=android",
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "-x",
                "--audio-format", "mp3",
                "-o", outputPath,
                "--no-warnings",
                url
        };

        executeCommand(command);

        // Поиск скачанного файла (ожидается .mp3)
        Path expectedFile = outputDir.resolve(fileNameWithoutExt + ".mp3");
        if (Files.exists(expectedFile)) {
            return expectedFile.toFile();
        } else {
            throw new IOException("Скачанный аудио файл не найден: " + expectedFile);
        }
    }

    private void executeCommand(String[] command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[YTDLP] {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Команда yt-dlp завершилась с кодом: " + exitCode);
        }
    }

    public String getYtDlpPath() {
        return ytDlpPath;
    }
}
