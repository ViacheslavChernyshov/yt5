package com.maslen.youtubelizer.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maslen.youtubelizer.util.PathUtils;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LlamaService {

    private final ObjectMapper objectMapper;
    private final String llamaPathConfigured;
    private final String modelPathConfigured;
    private final int serverPort;
    private final String serverHost;
    private final int serverTimeout;
    private final int threads;
    private final String extraParams;

    private String llamaPath;
    private String modelPath;

    private Process serverProcess;
    private volatile boolean serverStarting = false;

    private static final String NORMALIZATION_PROMPT = """
            Ты - корректор текста. Твоя задача - нормализовать и исправить входной текст.

            ВАЖНЫЕ ПРАВИЛА:
            1. НЕ меняй язык текста - оставляй его на исходном языке
            2. Исправляй ТОЛЬКО:
               - Грамматические ошибки
               - Орфографические ошибки
               - Ошибки пунктуации
               - Неверные падежи слов
               - Неверное согласование слов в предложениях
            3. Сохраняй текст максимально близким к оригиналу - не перефразируй и не переписывай
            4. Не добавляй никаких объяснений или комментариев - выводи ТОЛЬКО исправленный текст
            5. Сохраняй исходный смысл в точности

            Входной текст:
            %s

            Исправленный текст:
            """;

    public LlamaService(
            ObjectMapper objectMapper,
            @Value("${app.llama.path:}") String llamaPathConfigured,
            @Value("${app.llama.model.path:}") String modelPathConfigured,
            @Value("${app.llama.server.port:8081}") int serverPort,
            @Value("${app.llama.server.host:localhost}") String serverHost,
            @Value("${app.llama.server.timeout:300000}") int serverTimeout,
            @Value("${app.llama.threads:20}") int threads,
            @Value("${app.llama.extra-params:}") String extraParams) {
        this.objectMapper = objectMapper;
        this.llamaPathConfigured = llamaPathConfigured;
        this.modelPathConfigured = modelPathConfigured;
        this.serverPort = serverPort;
        this.serverHost = serverHost;
        this.serverTimeout = serverTimeout;
        this.threads = threads;
        this.extraParams = extraParams;
    }

    @PostConstruct
    private void initializePaths() {
        // Initialize llama server path with platform-specific default
        String defaultBinary = "llama-server";
        String defaultLlamaPath = Paths.get("llama", defaultBinary).toAbsolutePath().normalize().toString();
        llamaPath = PathUtils.resolvePath(llamaPathConfigured, defaultLlamaPath);

        // Initialize model path
        String defaultModelPath = Paths.get("llama", "models", "qwen2.5-7b-instruct-q3_k_m.gguf")
                .toAbsolutePath().normalize().toString();
        modelPath = PathUtils.resolvePath(modelPathConfigured, defaultModelPath);

        log.info("[LLAMA] Initialized paths - server: {}, model: {}", llamaPath, modelPath);
    }

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(llamaPath);
        Path modelFilePath = Paths.get(modelPath);

        if (Files.exists(exePath)) {
            log.info("[LLAMA] Binary found at: {}", llamaPath);
            if (!Files.isExecutable(exePath)) {
                log.warn("[LLAMA] Setting executable permissions...");
                exePath.toFile().setExecutable(true);
            }
        } else {
            log.error("[LLAMA] Binary not found at: {}", llamaPath);
        }

        if (Files.exists(modelFilePath)) {
            log.info("[LLAMA] Model found at: {}", modelPath);
        } else {
            log.warn("[LLAMA] Model not found at: {}", modelPath);
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

        // Check if binary exists before attempting to start
        Path exePath = Paths.get(llamaPath);
        if (Files.notExists(exePath)) {
            throw new IOException("[LLAMA] Binary not found at: " + llamaPath +
                    ". Please install llama.cpp or provide the binary path.");
        }

        serverStarting = true;
        try {
            log.info("[LLAMA] Starting llama-server on port {}...", serverPort);

            List<String> command = new ArrayList<>();
            command.add(llamaPath);
            command.add("-m");
            command.add(modelPath);
            command.add("--port");
            command.add(String.valueOf(serverPort));
            command.add("--host");
            command.add("0.0.0.0");
            command.add("-c");
            command.add("1024");
            command.add("-t");
            command.add(String.valueOf(threads));
            command.add("--flash-attn");
            command.add("off");
            command.add("--no-mmap");

            // Add extra params if present
            if (extraParams != null && !extraParams.isBlank()) {
                String[] parts = extraParams.split("\\s+");
                for (String part : parts) {
                    if (!part.isBlank()) {
                        command.add(part);
                    }
                }
            }

            ProcessBuilder pb = new ProcessBuilder(command);

            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            // Read server logs in a separate thread
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

            // Wait for server to start
            if (!waitForServer(120)) {
                throw new IOException("llama-server failed to start within 120 seconds");
            }

            log.info("[LLAMA] Server successfully started on port {}", serverPort);
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

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("qwen2.5")
                .messages(List.of(new Message("user", prompt)))
                .temperature(0.1)
                .maxTokens(1024)
                .stream(false)
                .build();

        String requestBody = objectMapper.writeValueAsString(request);

        log.info("[LLAMA] Отправка запроса нормализации для текста длиной {} символов, язык: {}",
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
            try (InputStream errorStream = conn.getErrorStream()) {
                String error = readStream(errorStream);
                throw new IOException("llama-server вернул ошибку " + responseCode + ": " + error);
            }
        }

        String response;
        try (InputStream inputStream = conn.getInputStream()) {
            response = readStream(inputStream);
        }
        conn.disconnect();

        // Парсим ответ
        ChatCompletionResponse completionResponse = objectMapper.readValue(response, ChatCompletionResponse.class);

        if (completionResponse.getChoices() == null || completionResponse.getChoices().isEmpty() ||
                completionResponse.getChoices().get(0).getMessage() == null) {
            log.warn("[LLAMA] Empty response from server");
            return "";
        }

        String normalizedText = completionResponse.getChoices().get(0).getMessage().getContent();
        log.info("[LLAMA] Нормализация завершена, результат: {} символов", normalizedText.length());

        return normalizedText != null ? normalizedText.trim() : "";
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

    @PreDestroy
    public void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            log.info("[LLAMA] Остановка llama-server...");
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

    // DTO Helper Classes
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ChatCompletionRequest {
        private String model;
        private List<Message> messages;
        private double temperature;
        @JsonProperty("max_tokens")
        private int maxTokens;
        private boolean stream;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Message {
        private String role;
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ChatCompletionResponse {
        private List<Choice> choices;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Choice {
        private Message message;
    }
}
