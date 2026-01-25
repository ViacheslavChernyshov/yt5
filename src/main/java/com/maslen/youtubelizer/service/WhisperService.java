package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.util.DownloadHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class WhisperService {

    @Value("${app.whisper.path:./whisper/whisper-cli.exe}")
    private String whisperPath;

    @Value("${app.whisper.model.path:./whisper/models/ggml-large-v3.bin}")
    private String modelPath;

    private static final String WHISPER_URL = "https://github.com/ggerganov/whisper.cpp/releases/download/v1.8.2/whisper-cublas-12.4.0-bin-x64.zip";
    private static final String MODEL_DOWNLOAD_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin";

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(whisperPath);
        Path modelFilePath = Paths.get(modelPath);

        // Download binary if missing
        if (Files.notExists(exePath)) {
            log.info("[WHISPER] Downloading Whisper...");
            DownloadHelper.downloadAndExtractZip(WHISPER_URL, exePath.getParent(), "Whisper");
            exePath.toFile().setExecutable(true);
        } else {
            log.info("[WHISPER] Found: {}", whisperPath);
        }

        // Download model if missing
        if (Files.notExists(modelFilePath)) {
            log.info("[WHISPER] Downloading ggml-large-v3 model...");
            DownloadHelper.downloadWithProgress(MODEL_DOWNLOAD_URL, modelFilePath, "Whisper model");
        } else {
            log.info("[WHISPER] Model found: {}", modelPath);
        }
    }

    public String getWhisperPath() {
        return whisperPath;
    }

    public String getModelPath() {
        return modelPath;
    }
}
