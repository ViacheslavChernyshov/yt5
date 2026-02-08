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

    @PostConstruct
    private void initializePaths() {
        // Initialize whisper executable path
        if (whisperPath == null || whisperPath.isEmpty()) {
            String binaryName = isWindows() ? "whisper-cli.exe" : "whisper-cli";
            whisperPath = Paths.get("whisper", binaryName).toAbsolutePath().normalize().toString();
        } else if (!Paths.get(whisperPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            whisperPath = Paths.get(whisperPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            whisperPath = Paths.get(whisperPath).normalize().toString();
        }
        
        // Initialize model path
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = Paths.get("whisper", "models", "ggml-large-v3.bin").toAbsolutePath().normalize().toString();
        } else if (!Paths.get(modelPath).isAbsolute()) {
            // Convert relative paths to absolute paths relative to application root
            modelPath = Paths.get(modelPath).toAbsolutePath().normalize().toString();
        } else {
            // Normalize absolute paths to remove redundant components
            modelPath = Paths.get(modelPath).normalize().toString();
        }
        
        log.info("[WHISPER] Initialized paths - executable: {}, model: {}", whisperPath, modelPath);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(whisperPath);
        Path modelFilePath = Paths.get(modelPath);

        // Only log the paths, no downloading
        if (Files.exists(exePath)) {
            log.info("[WHISPER] Binary found at: {}", whisperPath);
            if (!isWindows() && !Files.isExecutable(exePath)) {
                log.warn("[WHISPER] Binary exists but is not executable, setting permissions...");
                exePath.toFile().setExecutable(true);
            }
        } else {
            log.error("[WHISPER] Binary not found at: {}. Please ensure it's installed.", whisperPath);
        }

        if (Files.exists(modelFilePath)) {
            log.info("[WHISPER] Model found at: {}", modelPath);
            File modelFile = modelFilePath.toFile();
            if (modelFile.length() < 100000000) {
                log.warn("[WHISPER] Model file size is suspiciously small ({} bytes)", modelFile.length());
            }
        } else {
            log.warn("[WHISPER] Model not found at: {}. Please download it manually.", modelPath);
        }
    }

    public boolean isModelValid() {
        Path modelFilePath = Paths.get(modelPath);
        if (Files.notExists(modelFilePath)) {
            log.warn("[WHISPER] Модель не найдена: {}", modelPath);
            return false;
        }
        return true;
    }

    /**
     * Транскрибирует аудиофайл с использованием Whisper
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
     * Транскрибирует аудиофайл с использованием Whisper и возвращает как
     * транскрипцию, так и
     * обнаруженный язык
     * 
     * @param audioFile Путь к аудиофайлу для транскрипции
     * @return Массив объектов с [транскрипция, язык]
     * @throws IOException          Если возникла проблема с файловыми операциями
     * @throws InterruptedException Если процесс был прерван
     */
    public Object[] transcribeWithLanguage(File audioFile) throws IOException, InterruptedException {
        log.info("[WHISPER] Начало транскрипции с определением языка для файла: {}", audioFile.getAbsolutePath());

        // Создаем временный базовый путь для вывода
        // Создаем временный файл, чтобы получить уникальный путь, затем удаляем его,
        // чтобы Whisper мог
        // создать свои собственные файлы с расширениями
        Path tempBaseFile = Files.createTempFile("whisper_out_lang_", "");
        Files.deleteIfExists(tempBaseFile);
        String basePath = tempBaseFile.toAbsolutePath().toString();

        // Ожидаемые выходные файлы
        Path jsonOutputFile = Paths.get(basePath + ".json");
        Path txtOutputFile = Paths.get(basePath + ".txt");

        try {
            // Формирование команды whisper - использование CPU с 4 потоками
            String[] command = {
                    whisperPath,
                    "-m", modelPath,
                    "-f", audioFile.getAbsolutePath(),
                    "-of", basePath, // Базовый путь выходного файла
                    "--output-txt", // Вывод в текстовом формате
                    "-oj", // Вывод в формате JSON
                    "--threads", String.valueOf(threads), // Использовать настроенное количество потоков
                    useGpu ? "" : "-ng", // Условно отключить использование GPU
                    "--language", "auto", // Автоопределение языка
                    "--word-thold", "0.01" // Нижний порог обнаружения слов
            };

            // Удаление пустых аргументов
            java.util.List<String> cmdList = new java.util.ArrayList<>();
            for (String arg : command) {
                if (arg != null && !arg.isEmpty()) {
                    cmdList.add(arg);
                }
            }
            command = cmdList.toArray(new String[0]);

            log.debug("[WHISPER] Выполнение команды: {}", String.join(" ", command));

            // Выполнение команды
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Чтение вывода процесса
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[WHISPER] {}", line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Команда Whisper завершилась с кодом: " + exitCode + ", вывод: " + output.toString());
            }

            String detectedLanguage = "unknown";
            String transcription = "";

            try {
                if (Files.exists(jsonOutputFile)) {
                    // Чтение выходного файла JSON, содержащего язык и транскрипцию
                    String jsonOutput = Files.readString(jsonOutputFile);
                    log.debug("Содержимое вывода JSON: {}", jsonOutput);

                    // Извлечение языка из JSON
                    if (jsonOutput.contains("\"language\"")) {
                        int langStart = jsonOutput.indexOf("\"language\":\"") + "\"language\":\"".length();
                        int langEnd = jsonOutput.indexOf("\"", langStart);
                        if (langStart > -1 && langEnd > langStart) {
                            detectedLanguage = jsonOutput.substring(langStart, langEnd);
                        }
                    }

                    // Извлечение транскрипции из JSON - поиск текстовых сегментов
                    // Более надежный подход для извлечения всего текста из сегментов
                    if (jsonOutput.contains("\"segments\"")) {
                        // Поиск массива сегментов и извлечение всех текстовых значений
                        StringBuilder transcriptBuilder = new StringBuilder();

                        // Поиск начала и конца массива сегментов
                        int segmentsStart = jsonOutput.indexOf("\"segments\"");
                        if (segmentsStart != -1) {
                            int arrayStart = jsonOutput.indexOf("[", segmentsStart);
                            if (arrayStart != -1) {
                                // Поиск соответствующей закрывающей скобки для массива
                                int bracketCount = 0;
                                int pos = arrayStart;
                                for (; pos < jsonOutput.length(); pos++) {
                                    char c = jsonOutput.charAt(pos);
                                    if (c == '[')
                                        bracketCount++;
                                    else if (c == ']')
                                        bracketCount--;

                                    if (bracketCount == 0) {
                                        break;
                                    }
                                }

                                if (pos < jsonOutput.length()) {
                                    String segmentsArray = jsonOutput.substring(arrayStart, pos + 1);

                                    // Извлечение всех текстовых полей из сегментов
                                    int textIndex = 0;
                                    while ((textIndex = segmentsArray.indexOf("\"text\":\"", textIndex)) != -1) {
                                        textIndex += "\"text\":\"".length();
                                        int textEnd = segmentsArray.indexOf("\"", textIndex);

                                        if (textEnd != -1) {
                                            String segmentText = segmentsArray.substring(textIndex, textEnd);
                                            // Правильное деэкранирование JSON строк
                                            segmentText = segmentText.replace("\\\"", "\"")
                                                    .replace("\\n", "\n")
                                                    .replace("\\t", "\t")
                                                    .replace("\\r", "\r");
                                            transcriptBuilder.append(segmentText.trim()).append(" ");
                                            textIndex = textEnd; // Продолжение поиска после этой позиции
                                        }
                                    }
                                }
                            }
                        }

                        transcription = transcriptBuilder.toString().trim();
                    }
                } else {
                    log.warn("[WHISPER] Файл вывода JSON не найден: {}", jsonOutputFile);
                }
            } catch (Exception e) {
                log.warn("Не удалось разобрать вывод JSON, переход к текстовому файлу: {}", e.getMessage());
            }

            // Переход к чтению текстового файла
            if (transcription.isEmpty()) {
                try {
                    if (Files.exists(txtOutputFile)) {
                        transcription = Files.readString(txtOutputFile);
                        log.debug("Содержимое резервной транскрипции: {}", transcription);

                        // Извлечение языка из текстового файла, если он еще не извлечен
                        if (detectedLanguage.equals("unknown") && transcription.contains("auto-detected language:")) {
                            int langStart = transcription.indexOf("auto-detected language:")
                                    + "auto-detected language:".length();
                            int spaceEnd = transcription.indexOf(" ", langStart);
                            int newlineEnd = transcription.indexOf("\n", langStart);
                            int endPos = Math.min(
                                    spaceEnd != -1 ? spaceEnd : Integer.MAX_VALUE,
                                    newlineEnd != -1 ? newlineEnd : Integer.MAX_VALUE);
                            if (endPos != Integer.MAX_VALUE) {
                                detectedLanguage = transcription.substring(langStart, endPos).trim();
                                if (detectedLanguage.endsWith(",")) {
                                    detectedLanguage = detectedLanguage.substring(0, detectedLanguage.length() - 1)
                                            .trim();
                                }
                            }
                        }
                    } else {
                        log.warn("[WHISPER] Текстовый файл вывода не найден: {}", txtOutputFile);
                    }
                } catch (IOException ioEx) {
                    log.error("Не удалось прочитать текстовый файл вывода: {}", ioEx.getMessage());
                }
            }

            log.info("[WHISPER] Транскрипция успешно завершена, язык: {}, длина: {} символов", detectedLanguage,
                    transcription.length());
            return new Object[] { transcription.trim(), detectedLanguage };
        } finally {
            // Очистка временных файлов
            try {
                Files.deleteIfExists(jsonOutputFile);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный файл транскрипции JSON: {}", jsonOutputFile, e);
            }
            try {
                Files.deleteIfExists(txtOutputFile);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный файл транскрипции: {}", txtOutputFile, e);
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
