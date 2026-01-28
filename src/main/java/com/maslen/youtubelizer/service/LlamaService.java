package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.util.DownloadHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LlamaService {

    @Value("${app.llama.path:./llama/llama-server.exe}")
    private String llamaPath;

    @Value("${app.llama.model.path:./llama/models/qwen2.5-7b-instruct-q3_k_m.gguf}")
    private String modelPath;

    @Value("${app.llama.server.port:8081}")
    private int serverPort;

    @Value("${app.llama.server.host:localhost}")
    private String serverHost;

    @Value("${app.llama.server.timeout:300000}")
    private int serverTimeout;

    @Value("${app.llama.threads:20}")
    private int threads;

    private static final String LLAMA_URL = "https://github.com/ggml-org/llama.cpp/releases/download/b7240/llama-b7240-bin-win-vulkan-x64.zip";
    private static final String MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q3_k_m.gguf";

    private Process serverProcess;
    private volatile boolean serverStarting = false;

    private static final String NORMALIZATION_PROMPT = """
            You are a text proofreader. Your task is to normalize and correct the input text.

            IMPORTANT RULES:
            1. DO NOT change the language of the text - keep it in the original language
            2. Fix ONLY:
               - Grammar errors
               - Spelling errors
               - Punctuation errors
               - Incorrect word cases (падежи)
               - Incorrect word agreement in sentences
            3. Keep the text as close as possible to the original - do not rephrase or rewrite
            4. Do not add any explanations or comments - output ONLY the corrected text
            5. Preserve the original meaning exactly

            Input text:
            %s

            Corrected text:
            """;

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

    /**
     * Запускает llama-server если он ещё не запущен
     */
    public synchronized void ensureServerRunning() throws IOException, InterruptedException {
        if (isServerRunning()) {
            log.debug("[LLAMA] Server already running on port {}", serverPort);
            return;
        }

        if (serverStarting) {
            log.info("[LLAMA] Server is starting, waiting...");
            waitForServer(60);
            return;
        }

        serverStarting = true;
        try {
            log.info("[LLAMA] Starting llama-server on port {}...", serverPort);

            ProcessBuilder pb = new ProcessBuilder(
                    llamaPath,
                    "-m", modelPath,
                    "--port", String.valueOf(serverPort),
                    "--host", "0.0.0.0",
                    "-c", "4096", // context size
                    "-t", String.valueOf(threads) // CPU threads (20 by default)
            );

            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            // Читаем логи сервера в отдельном потоке
            Thread logReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("[LLAMA-SERVER] {}", line);
                    }
                } catch (IOException e) {
                    log.error("[LLAMA] Error reading server output", e);
                }
            });
            logReader.setDaemon(true);
            logReader.start();

            // Ждём пока сервер запустится
            if (!waitForServer(120)) {
                throw new IOException("llama-server failed to start within 120 seconds");
            }

            log.info("[LLAMA] Server started successfully on port {}", serverPort);
        } finally {
            serverStarting = false;
        }
    }

    /**
     * Проверяет доступность сервера
     */
    public boolean isServerRunning() {
        try {
            URL url = new URL(String.format("http://%s:%d/health", serverHost, serverPort));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ожидает запуска сервера
     */
    private boolean waitForServer(int timeoutSeconds) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeout) {
            if (isServerRunning()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    /**
     * Нормализует текст с помощью нейросети
     * 
     * @param text     Текст для нормализации
     * @param language Язык текста (для информации, не изменяется)
     * @return Нормализованный текст
     */
    public String normalizeText(String text, String language) throws IOException, InterruptedException {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }

        // Убеждаемся что сервер запущен
        ensureServerRunning();

        String prompt = String.format(NORMALIZATION_PROMPT, text);

        // Формируем JSON запрос в формате OpenAI API
        String requestBody = String.format("""
                {
                    "model": "qwen2.5",
                    "messages": [
                        {
                            "role": "user",
                            "content": %s
                        }
                    ],
                    "temperature": 0.1,
                    "max_tokens": 4096,
                    "stream": false
                }
                """, escapeJson(prompt));

        log.info("[LLAMA] Sending normalization request for text of {} chars, language: {}",
                text.length(), language);

        // Отправляем запрос
        URL url = new URL(String.format("http://%s:%d/v1/chat/completions", serverHost, serverPort));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(serverTimeout);
        conn.setReadTimeout(serverTimeout);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readStream(conn.getErrorStream());
            throw new IOException("llama-server returned error " + responseCode + ": " + error);
        }

        String response = readStream(conn.getInputStream());
        conn.disconnect();

        // Парсим ответ
        String normalizedText = extractContentFromResponse(response);

        log.info("[LLAMA] Normalization completed, result: {} chars", normalizedText.length());
        return normalizedText;
    }

    /**
     * Читает поток в строку
     */
    private String readStream(InputStream is) throws IOException {
        if (is == null)
            return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /**
     * Экранирует строку для JSON
     */
    private String escapeJson(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    /**
     * Извлекает content из ответа OpenAI API
     */
    private String extractContentFromResponse(String response) {
        // Простой парсинг JSON ответа
        // Формат: {"choices":[{"message":{"content":"..."}}]}
        try {
            int contentStart = response.indexOf("\"content\":");
            if (contentStart == -1) {
                log.warn("[LLAMA] Could not find 'content' in response: {}", response);
                return "";
            }

            contentStart = response.indexOf("\"", contentStart + 10) + 1;
            int contentEnd = findClosingQuote(response, contentStart);

            if (contentEnd == -1) {
                log.warn("[LLAMA] Could not find closing quote in response");
                return "";
            }

            String content = response.substring(contentStart, contentEnd);
            // Распарсить JSON escapes
            content = content
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");

            return content.trim();
        } catch (Exception e) {
            log.error("[LLAMA] Error parsing response: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Находит закрывающую кавычку с учётом escape-последовательностей
     */
    private int findClosingQuote(String text, int startIndex) {
        boolean escaped = false;
        for (int i = startIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    @PreDestroy
    public void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            log.info("[LLAMA] Stopping llama-server...");
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
