package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WhisperService {

    @Value("${app.whisper.path:}")
    private String whisperPath;

    @Value("${app.whisper.model.path:}")
    private String modelPath;

    @Value("${app.whisper.use-gpu:false}")
    private boolean useGpu;

    @Value("${app.whisper.threads:4}")
    private int threads;

    @Value("${app.whisper.beam-size:5}")
    private int beamSize;

    @Value("${app.whisper.best-of:5}")
    private int bestOf;

    @Value("${app.whisper.gpu-device:0}")
    private int gpuDevice;

    @Value("${app.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    /**
     * Regex для парсинга временных меток whisper.cpp: [HH:MM:SS.mmm -->
     * HH:MM:SS.mmm] Text
     */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s*-->\\s*\\d{2}:\\d{2}:\\d{2}\\.\\d{3}]\\s*(.+)");

    /** Regex для определения языка из вывода whisper.cpp */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "auto-detected\\s+language:\\s+(\\w+)");

    /** Таймаут транскрипции — 30 минут */
    private static final long TRANSCRIPTION_TIMEOUT_MINUTES = 30;

    @PostConstruct
    private void initializePaths() {
        // Whisper binary path
        if (whisperPath == null || whisperPath.isEmpty()) {
            whisperPath = "whisper-cli";
        } else if (!Paths.get(whisperPath).isAbsolute()) {
            whisperPath = Paths.get(whisperPath).toAbsolutePath().normalize().toString();
        } else {
            whisperPath = Paths.get(whisperPath).normalize().toString();
        }

        // Model path
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = Paths.get("/app/whisper/ggml-large-v3.bin").toString();
        } else if (!Paths.get(modelPath).isAbsolute()) {
            modelPath = Paths.get(modelPath).toAbsolutePath().normalize().toString();
        } else {
            modelPath = Paths.get(modelPath).normalize().toString();
        }

        log.info("[WHISPER] whisper-cli path: {}", whisperPath);
        log.info("[WHISPER] Model path: {}", modelPath);
        log.info("[WHISPER] GPU enabled: {}, device: {}", useGpu, gpuDevice);
        log.info("[WHISPER] Threads: {}, Beam size: {}, Best-of: {}", threads, beamSize, bestOf);
    }

    public void ensureAvailable() throws IOException {
        log.info("[WHISPER] Проверка доступности whisper-cli...");

        // Check binary exists
        Path binaryPath = Paths.get(whisperPath);
        if (binaryPath.isAbsolute() && !Files.exists(binaryPath)) {
            throw new IOException("whisper-cli binary not found at: " + whisperPath);
        }

        // Check model exists
        Path modelFilePath = Paths.get(modelPath);
        if (!Files.exists(modelFilePath)) {
            throw new IOException("Whisper model not found at: " + modelPath);
        }

        long modelSize = Files.size(modelFilePath);
        log.info("[WHISPER] Model file size: {} MB", modelSize / (1024 * 1024));

        // Test binary with --help
        try {
            ProcessBuilder pb = new ProcessBuilder(whisperPath, "--help");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("whisper-cli --help timed out");
            }

            log.info("[WHISPER] ✅ whisper-cli доступен и готов к работе");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Проверка whisper-cli прервана", e);
        }
    }

    public boolean isModelValid() {
        try {
            Path modelFilePath = Paths.get(modelPath);
            return Files.exists(modelFilePath) && Files.size(modelFilePath) > 0;
        } catch (IOException e) {
            log.warn("[WHISPER] Ошибка проверки модели: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Транскрибирует аудиофайл с использованием whisper.cpp
     *
     * @param audioFile Путь к аудиофайлу для транскрипции
     * @return Транскрибированный текст
     * @throws IOException          Если возникла проблема с файловыми операциями
     * @throws InterruptedException Если процесс был прерван
     */
    public String transcribe(File audioFile) throws IOException, InterruptedException {
        Object[] result = transcribeWithLanguage(audioFile);
        return (String) result[0];
    }

    /**
     * Транскрибирует аудиофайл с использованием whisper.cpp и возвращает
     * транскрипцию и обнаруженный язык.
     *
     * Процесс:
     * 1. Конвертация аудио в WAV 16kHz mono (требование whisper.cpp)
     * 2. Запуск whisper-cli с оптимальными параметрами
     * 3. Парсинг вывода (временные метки + текст)
     * 4. Очистка временных файлов
     *
     * @param audioFile Путь к аудиофайлу для транскрипции
     * @return Массив объектов с [транскрипция, язык]
     * @throws IOException          Если возникла проблема с файловыми операциями
     * @throws InterruptedException Если процесс был прерван
     */
    public Object[] transcribeWithLanguage(File audioFile) throws IOException, InterruptedException {
        log.info("[WHISPER] Начало транскрипции: {}", audioFile.getAbsolutePath());

        // Step 1: Convert to WAV 16kHz mono (whisper.cpp requirement)
        File wavFile = convertToWav(audioFile);

        Process process = null;
        try {
            // Step 2: Build whisper-cli command
            List<String> command = buildWhisperCommand(wavFile);
            log.debug("[WHISPER] Команда: {}", String.join(" ", command));

            // Step 3: Execute whisper-cli
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            // Step 4: Read and parse output
            StringBuilder fullOutput = new StringBuilder();
            StringBuilder transcriptionBuilder = new StringBuilder();
            String detectedLanguage = "unknown";

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[WHISPER] {}", line);
                    fullOutput.append(line).append("\n");

                    // Parse language detection
                    Matcher langMatcher = LANGUAGE_PATTERN.matcher(line);
                    if (langMatcher.find()) {
                        detectedLanguage = langMatcher.group(1).trim();
                        log.info("[WHISPER] Обнаружен язык: {}", detectedLanguage);
                        continue;
                    }

                    // Parse transcription segments
                    Matcher tsMatcher = TIMESTAMP_PATTERN.matcher(line);
                    if (tsMatcher.find()) {
                        String segmentText = tsMatcher.group(1).trim();
                        if (!segmentText.isEmpty()) {
                            transcriptionBuilder.append(segmentText).append(" ");
                        }
                    }
                }
            }

            // Step 5: Wait for process with timeout
            boolean finished = process.waitFor(TRANSCRIPTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Транскрипция превысила таймаут " + TRANSCRIPTION_TIMEOUT_MINUTES + " минут");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException(
                        "whisper-cli завершился с кодом: " + exitCode + ", вывод: " + fullOutput);
            }

            String transcription = transcriptionBuilder.toString().trim();

            // Fallback parsing if main pattern didn't match
            if (transcription.isEmpty()) {
                transcription = fallbackParsing(fullOutput.toString());
            }

            // Normalize whitespace
            transcription = transcription.replaceAll("\\s+", " ").trim();

            log.info("[WHISPER] Транскрипция завершена. Язык: {}, длина: {} символов",
                    detectedLanguage, transcription.length());

            return new Object[] { transcription, detectedLanguage };

        } catch (InterruptedException e) {
            // Properly handle interruption
            if (process != null) {
                process.destroyForcibly();
                log.warn("[WHISPER] Процесс транскрипции принудительно завершён из-за прерывания");
            }
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            log.error("[WHISPER] Ошибка транскрипции: {}", e.getMessage(), e);
            throw e;
        } finally {
            // Cleanup WAV file
            cleanupWavFile(wavFile, audioFile);
        }
    }

    /**
     * Конвертирует аудиофайл в WAV 16kHz mono с использованием ffmpeg.
     * whisper.cpp работает оптимально с WAV PCM 16-bit, 16kHz, mono.
     */
    private File convertToWav(File audioFile) throws IOException, InterruptedException {
        String inputPath = audioFile.getAbsolutePath();

        // If already a 16kHz WAV, skip conversion
        if (inputPath.endsWith(".wav")) {
            log.debug("[WHISPER] Файл уже в формате WAV, пропускаем конвертацию");
            return audioFile;
        }

        // Create WAV file path
        String wavPath = inputPath.replaceAll("\\.[^.]+$", "") + "_16k.wav";
        File wavFile = new File(wavPath);

        log.info("[WHISPER] Конвертация {} -> WAV 16kHz mono...", audioFile.getName());

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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[FFMPEG] {}", line);
            }
        }

        boolean finished = p.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("ffmpeg конвертация превысила таймаут 5 минут");
        }

        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg конвертация завершилась с ошибкой, код: " + p.exitValue());
        }

        if (!wavFile.exists() || wavFile.length() == 0) {
            throw new IOException("ffmpeg не создал WAV файл: " + wavPath);
        }

        log.info("[WHISPER] Конвертация завершена: {} ({} MB)",
                wavFile.getName(), wavFile.length() / (1024 * 1024));

        return wavFile;
    }

    /**
     * Строит команду whisper-cli с оптимальными параметрами для качества.
     */
    private List<String> buildWhisperCommand(File wavFile) {
        List<String> command = new ArrayList<>();
        command.add(whisperPath);

        // Model
        command.add("-m");
        command.add(modelPath);

        // Input file
        command.add("-f");
        command.add(wavFile.getAbsolutePath());

        // CPU threads
        command.add("-t");
        command.add(String.valueOf(threads));

        // Quality: Beam search
        if (beamSize > 1) {
            command.add("-bs");
            command.add(String.valueOf(beamSize));
        }

        // Quality: Best-of candidates
        if (bestOf > 1) {
            command.add("-bo");
            command.add(String.valueOf(bestOf));
        }

        // Language: auto-detect
        command.add("-l");
        command.add("auto");

        // GPU support
        if (useGpu) {
            command.add("--device");
            command.add(String.valueOf(gpuDevice));
        } else {
            command.add("--no-gpu");
        }

        // Output control: -np suppresses non-result output
        command.add("-np");

        return command;
    }

    /**
     * Запасной парсинг вывода, если основной regex не сработал.
     */
    private String fallbackParsing(String output) {
        log.warn("[WHISPER] Основной парсинг не нашёл текста, пробуем запасной...");

        // Detect if output is actually a help/usage message (not transcription)
        if (output.contains("usage:") && output.contains("--help")) {
            log.error("[WHISPER] Вывод содержит usage/help — whisper-cli получил неверные аргументы");
            return "";
        }

        StringBuilder result = new StringBuilder();

        // Pattern 1: timestamps with any format
        Pattern altPattern = Pattern.compile(
                "\\[\\d{2}:\\d{2}[:.\\d]+ -->\\s*\\d{2}:\\d{2}[:.\\d]+]\\s*(.+)");
        Matcher altMatcher = altPattern.matcher(output);
        int count = 0;
        while (altMatcher.find()) {
            String text = altMatcher.group(1).trim();
            if (!text.isEmpty()) {
                result.append(text).append(" ");
                count++;
            }
        }

        if (count > 0) {
            log.info("[WHISPER] Запасной парсинг нашёл {} сегментов", count);
        } else {
            // Pattern 2: try to extract any non-log text
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                // Skip log lines, empty lines, and CLI option descriptions
                if (!line.isEmpty()
                        && !line.startsWith("whisper_")
                        && !line.startsWith("ggml_")
                        && !line.startsWith("main:")
                        && !line.startsWith("system_info:")
                        && !line.startsWith("-")
                        && !line.startsWith("error:")
                        && !line.startsWith("options:")
                        && !line.startsWith("supported")
                        && !line.contains("auto-detected")
                        && !line.contains("processing")
                        && !line.contains("--")) {
                    result.append(line).append(" ");
                }
            }
            if (result.toString().trim().isEmpty()) {
                log.warn("[WHISPER] Все паттерны вернули пусто, вывод: {}", output);
            }
        }

        return result.toString().trim();
    }

    /**
     * Удаляет временный WAV файл, если он отличается от оригинала.
     */
    private void cleanupWavFile(File wavFile, File originalFile) {
        if (wavFile != null && !wavFile.equals(originalFile) && wavFile.exists()) {
            try {
                wavFile.delete();
                log.debug("[WHISPER] Удалён временный WAV: {}", wavFile.getName());
            } catch (Exception e) {
                log.warn("[WHISPER] Не удалось удалить временный WAV: {}", wavFile.getName());
            }
        }
    }

    public String getWhisperPath() {
        return whisperPath;
    }

    public String getModelPath() {
        return modelPath;
    }
}
