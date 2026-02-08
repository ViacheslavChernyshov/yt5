package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.util.DownloadHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    @Value("${app.whisper.path:./whisper/whisper-cli.exe}")
    private String whisperPath;

    @Value("${app.whisper.model.path:./whisper/models/ggml-large-v3.bin}")
    private String modelPath;

    @Value("${app.whisper.use-gpu:false}")
    private boolean useGpu;

    @Value("${app.whisper.threads:4}")
    private int threads;

    private static final String WHISPER_WINDOWS_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.3/whisper-blas-bin-x64.zip";
    private static final String WHISPER_LINUX_URL = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.3/whisper-blas-bin-x64.zip";
    private static final String MODEL_DOWNLOAD_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin";

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String getWhisperDownloadUrl() {
        return isWindows() ? WHISPER_WINDOWS_URL : WHISPER_LINUX_URL;
    }

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(whisperPath);
        Path modelFilePath = Paths.get(modelPath);

        // Check availability of binary
        if (Files.notExists(exePath)) {
            // Если путь абсолютный и в системе (например /usr/local/bin), мы не можем его просто скачать
            // Но если это локальный путь (например ./whisper/...), попробуем скачать
            if (!exePath.isAbsolute() || exePath.toString().contains("./")) {
                 log.info("[WHISPER] Скачивание Whisper для {}...", isWindows() ? "Windows" : "Linux");
                 DownloadHelper.downloadAndExtractZip(getWhisperDownloadUrl(), exePath.getParent(), "Whisper");
                 if (!isWindows()) {
                     exePath.toFile().setExecutable(true);
                 }
            } else {
                 log.warn("[WHISPER] Исполняемый файл не найден по пути: {}. Скачивание пропущено, так как это системный путь.", whisperPath);
            }
        } else {
            log.info("[WHISPER] Найден: {}", whisperPath);
            if (!isWindows() && !Files.isExecutable(exePath)) {
                 log.warn("[WHISPER] Файл не имеет прав на выполнение, пытаемся исправить...");
                 exePath.toFile().setExecutable(true);
            }
        }

        // Download model if missing
        if (Files.notExists(modelFilePath)) {
            log.info("[WHISPER] Скачивание модели ggml-large-v3...");
            DownloadHelper.downloadWithProgress(MODEL_DOWNLOAD_URL, modelFilePath, "Модель Whisper");
        } else {
            log.info("[WHISPER] Модель найдена: {}", modelPath);
            // Проверка целостности файла модели по размеру
            File modelFile = new File(modelPath);
            if (modelFile.length() < 100000000) { // Меньше 100 МБ, скорее всего, неполная загрузка
                log.warn("[WHISPER] Файл модели слишком мал ({} байт), возможно поврежден", modelFile.length());
            }
        }
    }

    public boolean isModelValid() {
        Path modelFilePath = Paths.get(modelPath);
        if (Files.notExists(modelFilePath)) {
            log.warn("[WHISPER] Файл модели не существует: {}", modelPath);
            return false;
        }

        File modelFile = modelFilePath.toFile();
        // Базовая валидация: файл модели large-v3 должен быть около 1.8GB для
        // квантованной версии
        // Наш файл около 115МБ, проверяем минимальный размер для обнаружения неполных
        // загрузок
        if (modelFile.length() < 100000000) { // Меньше 100 МБ
            log.warn("[WHISPER] Размер файла модели подозрительно мал ({} байт), вероятно поврежден",
                    modelFile.length());
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

        // Проверяем валидность модели перед продолжением
        if (!isModelValid()) {
            log.warn("[WHISPER] Валидация модели не удалась, попытка повторного скачивания...");
            ensureAvailable(); // Это приведет к повторному скачиванию модели, если она не существует или
                               // невалидна

            // Повторная проверка после скачивания
            if (!isModelValid()) {
                throw new RuntimeException("Модель Whisper все еще невалидна после попытки скачивания");
            }
        }

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
