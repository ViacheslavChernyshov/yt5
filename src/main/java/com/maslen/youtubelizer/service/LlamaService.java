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
public class LlamaService {

    @Value("${app.llama.path:./llama/llama-server.exe}")
    private String llamaPath;

    @Value("${app.llama.model.path:./llama/models/qwen2.5-7b-instruct-q3_k_m.gguf}")
    private String modelPath;

    private static final String LLAMA_URL = "https://github.com/ggml-org/llama.cpp/releases/download/b7240/llama-b7240-bin-win-vulkan-x64.zip";
    private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q3_k_m.gguf";

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(llamaPath);
        Path modelFilePath = Paths.get(modelPath);

        // Download llama.cpp if missing
        if (Files.notExists(exePath)) {
            log.info("[LLAMA] Downloading llama.cpp...");
            DownloadHelper.downloadAndExtractZip(LLAMA_URL, exePath.getParent(), "Llama.cpp");
            exePath.toFile().setExecutable(true);
        } else {
            log.info("[LLAMA] Found: {}", llamaPath);
        }

        // Download model if missing
        if (Files.notExists(modelFilePath)) {
            log.info("[LLAMA] Downloading Qwen2.5 model (this will take a few minutes)...");
            DownloadHelper.downloadWithProgress(MODEL_URL, modelFilePath, "Qwen model");
        } else {
            log.info("[LLAMA] Model found: {}", modelPath);
        }
    }

    public String getLlamaPath() {
        return llamaPath;
    }

    public String getModelPath() {
        return modelPath;
    }

    public boolean isAvailable() {
        return Files.exists(Paths.get(llamaPath)) && Files.exists(Paths.get(modelPath));
    }
}
