package com.maslen.youtubelizer.service;

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
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LlamaService {

    @Value("${app.llama.path:}")
    private String llamaPath;

    @Value("${app.llama.model.path:}")
    private String modelPath;

    @Value("${app.llama.server.port:8081}")
    private int serverPort;

    @Value("${app.llama.server.host:localhost}")
    private String serverHost;

    @Value("${app.llama.server.timeout:300000}")
    private int serverTimeout;

    @Value("${app.llama.threads:20}")
    private int threads;

    @PostConstruct
    private void initializePaths() {
        // Initialize llama server path
        if (llamaPath == null || llamaPath.isEmpty()) {
            String binaryName = isWindows() ? "llama-server.exe" : "llama-server";
            llamaPath = Paths.get("llama", binaryName).toAbsolutePath().normalize().toString();
        } else if (!Paths.get(llamaPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            llamaPath = Paths.get(llamaPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            llamaPath = Paths.get(llamaPath).normalize().toString();
        }

        // Initialize model path
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = Paths.get("llama", "models", "qwen2.5-7b-instruct-q3_k_m.gguf").toAbsolutePath().normalize()
                    .toString();
        } else if (!Paths.get(modelPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            modelPath = Paths.get(modelPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            modelPath = Paths.get(modelPath).normalize().toString();
        }

        log.info("[LLAMA] Initialized paths - server: {}, model: {}", llamaPath, modelPath);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

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

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(llamaPath);
        Path modelFilePath = Paths.get(modelPath);

        if (Files.exists(exePath)) {
            log.info("[LLAMA] Binary found at: {}", llamaPath);
            if (!isWindows() && !Files.isExecutable(exePath)) {
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

            ProcessBuilder pb = new ProcessBuilder(
                    llamaPath,
                    "-m", modelPath,
                    "--port", String.valueOf(serverPort),
                    "--host", "0.0.0.0",
                    "-c", "1024", // Context window: 1024 tokens saves ~3.5GB memory
                    "-t", "2", // Reduced threads to 2 for memory efficiency
                    "--flash-attn", "off", // Disable flash attention for compatibility
                    "--no-mmap" // Lower peak memory on repeated calls
            );

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
                    "max_tokens": 1024,
                    "stream": false
                }
                """, escapeJson(prompt));

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
            String error = readStream(conn.getErrorStream());
            throw new IOException("llama-server вернул ошибку " + responseCode + ": " + error);
        }

        String response = readStream(conn.getInputStream());
        conn.disconnect();

        // Парсим ответ
        String normalizedText = extractContentFromResponse(response);

        log.info("[LLAMA] Нормализация завершена, результат: {} символов", normalizedText.length());
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
                log.warn("[LLAMA] Не удалось найти 'content' в ответе: {}", response);
                return "";
            }

            contentStart = response.indexOf("\"", contentStart + 10) + 1;
            int contentEnd = findClosingQuote(response, contentStart);

            if (contentEnd == -1) {
                log.warn("[LLAMA] Не удалось найти закрывающую кавычку в ответе");
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
            log.error("[LLAMA] Ошибка парсинга ответа: {}", e.getMessage());
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
}
