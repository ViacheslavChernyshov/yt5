package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;

@Slf4j
@Service
public class FfmpegService {

    @Value("${app.ffmpeg.path:}")
    private String ffmpegPath;

    @PostConstruct
    private void initializePath() {
        ffmpegPath = PathUtils.resolvePath(ffmpegPath, "ffmpeg");
        log.info("[FFMPEG] Initialized path: {}", ffmpegPath);
    }

    public void ensureAvailable() throws IOException {
        // Path is configured via environment variables or defaults
        // FFmpeg is installed in Docker, no need to download
        log.info("[FFMPEG] Using configured path: {}", ffmpegPath);
    }

    public File convertToWav(File inputFile) throws IOException, InterruptedException {
        String inputPath = inputFile.getAbsolutePath();

        // If already a 16kHz WAV, skip conversion
        if (inputPath.endsWith(".wav")) {
            log.debug("[FFMPEG] File is already WAV, checking if it needs conversion is skipped for now");
            // In a real scenario, we might want to check sample rate/channels using ffprobe
            // But for now, let's assume .wav extension from our own downloads is fine,
            // or we force conversion if we want to be safe.
            // Let's stick to the original logic: if .wav, return it.
            // However, whisper.cpp STRICTLY needs 16kHz mono.
            // If the user uploads a random .wav, it might be 44.1kHz stereo.
            // Safest option: ALWAYS convert unless we know it's already processed.
            // But to avoid infinite loops or re-processing, let's check filename suffix.
            if (inputPath.endsWith("_16k.wav")) {
                return inputFile;
            }
        }

        // Create WAV file path
        String wavPath = inputPath.replaceAll("\\.[^.]+$", "") + "_16k.wav";
        File wavFile = new File(wavPath);

        // If file already exists and is not empty, use it
        if (wavFile.exists() && wavFile.length() > 0) {
            log.debug("[FFMPEG] Converted file already exists: {}", wavFile.getName());
            return wavFile;
        }

        log.info("[FFMPEG] Converting {} -> WAV 16kHz mono...", inputFile.getName());

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-i", inputPath,
                "-ar", "16000", // 16kHz sample rate
                "-ac", "1", // mono channel
                "-c:a", "pcm_s16le", // 16-bit PCM
                "-y", // overwrite output
                wavPath);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // Read output to prevent pipe blocking
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[FFMPEG] {}", line);
            }
        }

        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("ffmpeg conversion timed out after 5 minutes");
        }

        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg conversion failed with exit code: " + p.exitValue());
        }

        if (!wavFile.exists() || wavFile.length() == 0) {
            throw new IOException("ffmpeg did not create WAV file: " + wavPath);
        }

        log.info("[FFMPEG] Conversion completed: {} ({} MB)",
                wavFile.getName(), wavFile.length() / (1024 * 1024));

        return wavFile;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }
}
