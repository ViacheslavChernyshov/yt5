package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Request;
import com.maslen.youtubelizer.entity.Video;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.repository.RequestRepository;
import com.maslen.youtubelizer.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import com.maslen.youtubelizer.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.util.stream.Stream;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;

@Slf4j
@Service
public class TaskSchedulerService {

    @Autowired
    private DownloadTaskRepository downloadTaskRepository;

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private WhisperService whisperService;

    @Autowired
    private LlamaService llamaService;

    @Autowired
    private TelegramClient telegramClient;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private MessageService messageService;

    @Scheduled(fixedDelay = 10000) // Increased delay to 10 seconds to allow Flyway to run first
    public void processNextTask() {
        try {
            resetStuckTasks(); // Сбрасываем зависшие задачи перед началом новой

            Optional<DownloadTask> taskOpt = downloadTaskRepository
                    .findTopByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);

            if (taskOpt.isPresent()) {
                DownloadTask task = taskOpt.get();
                processTask(task);
            }
        } catch (Exception e) {
            log.warn("Ошибка обработки задач, возможно таблица download_tasks еще не создана: {}",
                    e.getMessage());
            // Это может произойти при запуске, если миграции Flyway еще не завершились
        }
    }

    /**
     * Сброс задач, которые находятся в состоянии PROCESSING более 1 часа
     */
    private void resetStuckTasks() {
        try {
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            List<DownloadTask> stuckTasks = downloadTaskRepository.findByStatusAndUpdatedAtBefore(TaskStatus.PROCESSING,
                    oneHourAgo);

            if (!stuckTasks.isEmpty()) {
                log.info("Найдено {} зависших задач, сбрасываем их в статус PENDING", stuckTasks.size());

                for (DownloadTask task : stuckTasks) {
                    log.warn("Сброс зависшей задачи id: {}, videoId: {}, последнее обновление: {}",
                            task.getId(), task.getVideoId(), task.getUpdatedAt());

                    task.setStatus(TaskStatus.PENDING); // Возвращаем в начальное состояние
                    downloadTaskRepository.save(task);
                }
            }
        } catch (Exception e) {
            log.warn("Ошибка сброса зависших задач, возможно таблица download_tasks еще не создана: {}",
                    e.getMessage());
            // Это может произойти при запуске, если миграции Flyway еще не завершились
        }
    }

    private void processTask(DownloadTask task) {
        log.info("Обработка задачи id: {} тип: {}", task.getId(), task.getType());

        task.setStatus(TaskStatus.PROCESSING);
        downloadTaskRepository.save(task);

        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            File file = null;

            if (task.getType() == TaskType.VIDEO) {
                file = ytDlpService.downloadVideo(url, Paths.get("downloads"), task.getVideoId());
                if (file != null && file.exists()) {
                    sendContent(task.getChatId(), file, task.getType().name(), task.getLanguageCode());
                    task.setStatus(TaskStatus.COMPLETED);

                    // Удаление файла после отправки
                    try {
                        file.delete();
                    } catch (Exception e) {
                        log.error("Не удалось удалить файл: {}", file.getAbsolutePath(), e);
                    }
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setErrorMessage("Video file not found after download");
                    sendMessage(task.getChatId(),
                            messageService.getMessage("error.download_failed", task.getLanguageCode()));
                }
            } else if (task.getType() == TaskType.AUDIO) {
                file = ytDlpService.downloadAudio(url, Paths.get("downloads"), task.getVideoId());
                if (file != null && file.exists()) {
                    sendContent(task.getChatId(), file, task.getType().name(), task.getLanguageCode());
                    task.setStatus(TaskStatus.COMPLETED);

                    // Удаление файла после отправки
                    try {
                        file.delete();
                    } catch (Exception e) {
                        log.error("Не удалось удалить файл: {}", file.getAbsolutePath(), e);
                    }
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setErrorMessage("Audio file not found after download");
                    sendMessage(task.getChatId(),
                            messageService.getMessage("error.download_failed", task.getLanguageCode()));
                }
            } else if (task.getType() == TaskType.SPEECH_RECOGNITION) {
                processSpeechRecognitionTask(task, url);
            } else if (task.getType() == TaskType.TEXT_NORMALIZATION) {
                processTextNormalizationTask(task);
            } else if (task.getType() == TaskType.FULL_PROCESSING_ZIP) {
                processFullProcessingZipTask(task);
            } else {
                throw new IllegalArgumentException("Неизвестный тип задачи: " + task.getType());
            }

        } catch (Exception e) {
            log.error("Ошибка при обработке задачи {}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setErrorMessage(e.getMessage());
            sendMessage(task.getChatId(),
                    messageService.getMessage("common.error", task.getLanguageCode()) + e.getMessage());
        }

        downloadTaskRepository.save(task);

        // Send donation menu only for successfully completed tasks
        if (task.getStatus() == TaskStatus.COMPLETED) {
            sendDonationMenu(task.getChatId(), task.getLanguageCode());
        }
    }

    private void processSpeechRecognitionTask(DownloadTask task, String url) throws IOException, InterruptedException {
        // Шаг 1: Проверяем, есть ли уже транскрипция в БД
        Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
        if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                && !videoOpt.get().getTranscriptionText().isEmpty()) {

            log.info("Найдена кэшированная транскрипция для видео: {}", task.getVideoId());
            sendTranscriptionToUser(task.getChatId(), videoOpt.get().getTranscriptionText(), task.getVideoId(),
                    task.getLanguageCode());
            task.setStatus(TaskStatus.COMPLETED);
            return;
        }

        // Шаг 2: Если не найдено, выполняем транскрипцию
        Video video = performTranscription(task, url);

        if (video != null) {
            // Шаг 3: Отправляем транскрипцию пользователю
            sendTranscriptionToUser(task.getChatId(), video.getTranscriptionText(), task.getVideoId(),
                    task.getLanguageCode());
            task.setStatus(TaskStatus.COMPLETED);
        }
    }

    private void processTextNormalizationTask(DownloadTask task) throws IOException, InterruptedException {
        String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
        log.info("[TEXT_NORMALIZATION] Начало нормализации для видео: {}", task.getVideoId());

        // Шаг 1: Проверяем, есть ли уже нормализованный текст в БД
        Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());

        if (videoOpt.isPresent() && videoOpt.get().getNormalizedText() != null
                && !videoOpt.get().getNormalizedText().isEmpty()) {

            log.info("Найден кэшированный нормализованный текст для видео: {}", task.getVideoId());
            sendNormalizedTextToUser(task.getChatId(), videoOpt.get().getNormalizedText(), task.getVideoId(),
                    task.getLanguageCode());
            task.setStatus(TaskStatus.COMPLETED);
            return;
        }

        Video video;
        // Шаг 2: Проверяем, есть ли транскрипция для нормализации
        if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                && !videoOpt.get().getTranscriptionText().isEmpty()) {
            video = videoOpt.get();
        } else {
            // Шаг 3: Нет транскрипции? Сначала нужно скачать и транскрибировать!
            // Шаг 3: Нет транскрипции? Сначала нужно скачать и транскрибировать!
            sendMessage(task.getChatId(), messageService.getMessage("common.transcribing", task.getLanguageCode()));
            video = performTranscription(task, url);
            if (video == null)
                return; // Ошибка произошла в performTranscription
        }

        try {
            // Шаг 4: Нормализация текста с помощью LLM
            String transcription = video.getTranscriptionText();
            String language = video.getOriginalLanguage();

            String normalizedText = llamaService.normalizeText(transcription, language);

            if (normalizedText == null || normalizedText.trim().isEmpty()) {
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage("Normalization returned empty result");
                sendMessage(task.getChatId(),
                        messageService.getMessage("common.error", task.getLanguageCode()) + " Empty result from LLM");
                return;
            }

            // Шаг 5: Сохранение нормализованного текста в базу данных
            video.setNormalizedText(normalizedText);
            videoRepository.save(video);
            log.info("[TEXT_NORMALIZATION] Сохранен нормализованный текст для видео: {}", task.getVideoId());

            // Шаг 6: Отправка нормализованного текста пользователю
            sendNormalizedTextToUser(task.getChatId(), normalizedText, task.getVideoId(), task.getLanguageCode());

            // Шаг 7: Отметка задачи как выполненной
            task.setStatus(TaskStatus.COMPLETED);
            log.info("[TEXT_NORMALIZATION] Нормализация завершена для видео: {}", task.getVideoId());

        } catch (Exception e) {
            log.error("[TEXT_NORMALIZATION] Ошибка нормализации текста для видео: {}", task.getVideoId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Error during normalization: " + e.getMessage());
            sendMessage(task.getChatId(),
                    messageService.getMessage("common.error", task.getLanguageCode()) + e.getMessage());
        }
    }

    /**
     * Вспомогательный метод для скачивания аудио, транскрипции и сохранения в БД.
     * Возвращает обновленную сущность Video или null в случае ошибки.
     */
    private Video performTranscription(DownloadTask task, String url) throws IOException, InterruptedException {
        // Шаг 1: Временное скачивание аудио
        File audioFile = ytDlpService.downloadAudio(url, Paths.get("temp"), "temp_" + task.getVideoId());

        if (audioFile == null || !audioFile.exists()) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Audio file not found after download");
            sendMessage(task.getChatId(), messageService.getMessage("error.download_failed", task.getLanguageCode()));
            return null;
        }

        try {
            return transcribeFile(task, audioFile);
        } finally {
            // Шаг 4: Очистка временного аудио файла
            try {
                if (audioFile.exists()) {
                    audioFile.delete();
                    log.debug("Удален временный аудио файл: {}", audioFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("Не удалось удалить временный аудио файл: {}", audioFile.getAbsolutePath(), e);
            }
        }
    }

    // Extracted method to transcribe ANY audio file
    private Video transcribeFile(DownloadTask task, File audioFile) throws IOException, InterruptedException {
        // Step 2: Transcribe the audio using Whisper with language detection
        log.info("Starting transcription with language detection for video: {}", task.getVideoId());
        Object[] result = whisperService.transcribeWithLanguage(audioFile);
        String transcription = (String) result[0];
        String detectedLanguage = (String) result[1];

        // Step 3: Save transcription result to database and return the Video entity
        return saveTranscriptionResult(task, transcription, detectedLanguage);
    }

    private void sendNormalizedTextToUser(Long chatId, String normalizedText, String videoId, String languageCode) {
        try {
            // If text is too long for a single message, split it
            if (normalizedText.length() > 4000) {
                String[] parts = splitString(normalizedText, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📝 %s (%d/%d):\n\n%s",
                            messageService.getMessage("common.normalizing", languageCode),
                            i + 1, parts.length, parts[i]);
                    sendMessage(chatId, part);
                    Thread.sleep(1000); // Small delay between messages
                }
            } else {
                String message = "✨ " + messageService.getMessage("common.normalizing", languageCode) + " " + videoId
                        + ":\n\n" + normalizedText;
                sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending normalized text to user", e);
        } catch (Exception e) {
            log.error("Failed to send normalized text to user", e);
            sendMessage(chatId, messageService.getMessage("common.error", languageCode) + " Failed to send text.");
        }
    }

    private void processFullProcessingZipTask(DownloadTask task) {
        log.info("[ZIP] Starting full processing for video: {}", task.getVideoId());
        Path tempDir = null;
        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            tempDir = Files.createTempDirectory("zip_" + task.getVideoId());

            // Step 1: Download Media (ALWAYS)
            sendMessage(task.getChatId(), messageService.getMessage("common.downloading", task.getLanguageCode()));
            File videoFile = ytDlpService.downloadVideo(url, tempDir, "video");
            File audioFile = ytDlpService.downloadAudio(url, tempDir, "audio");

            if (videoFile == null || !videoFile.exists() || audioFile == null || !audioFile.exists()) {
                throw new IOException("Failed to download media files");
            }

            // Step 2: Get Transcription
            sendMessage(task.getChatId(), messageService.getMessage("common.transcribing", task.getLanguageCode()));
            Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
            Video videoRecord;

            if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                    && !videoOpt.get().getTranscriptionText().isEmpty()) {
                log.info("[ZIP] Found existing transcription for {}", task.getVideoId());
                videoRecord = videoOpt.get(); // Use existing record
            } else {
                log.info("[ZIP] Transcription not found, recognizing from downloaded audio...");
                // Use the ALREADY downloaded audio file!
                videoRecord = transcribeFile(task, audioFile);
                if (videoRecord == null) {
                    throw new IOException("Transcription failed");
                }
            }

            // Step 3: Get Normalization
            sendMessage(task.getChatId(), messageService.getMessage("common.normalizing", task.getLanguageCode()));
            String normalizedText;
            if (videoRecord.getNormalizedText() != null && !videoRecord.getNormalizedText().isEmpty()) {
                log.info("[ZIP] Found existing normalization for {}", task.getVideoId());
                normalizedText = videoRecord.getNormalizedText();
            } else {
                log.info("[ZIP] Normalization not found, generating...");
                String transcription = videoRecord.getTranscriptionText();
                String language = videoRecord.getOriginalLanguage();

                normalizedText = llamaService.normalizeText(transcription, language);
                if (normalizedText == null) {
                    throw new IOException("Normalization returned empty result");
                }

                videoRecord.setNormalizedText(normalizedText);
                videoRepository.save(videoRecord);
            }

            // Step 4: Create Text Files
            File transcriptionFile = tempDir.resolve("transcription.txt").toFile();
            Files.writeString(transcriptionFile.toPath(), videoRecord.getTranscriptionText());

            File normalizedFile = tempDir.resolve("normalized.txt").toFile();
            Files.writeString(normalizedFile.toPath(), normalizedText);

            // Step 5: Pack ZIP
            sendMessage(task.getChatId(), messageService.getMessage("common.packing", task.getLanguageCode()));
            File zipFile = tempDir.resolve("content.zip").toFile();
            try (FileOutputStream fos = new FileOutputStream(zipFile);
                    ZipOutputStream zos = new ZipOutputStream(fos)) {

                addToZip(videoFile, zos);
                addToZip(audioFile, zos);
                addToZip(transcriptionFile, zos);
                addToZip(normalizedFile, zos);
            }

            // Step 6: Send ZIP
            sendMessage(task.getChatId(), messageService.getMessage("common.sending", task.getLanguageCode()));
            SendDocument sendDocument = SendDocument.builder()
                    .chatId(task.getChatId())
                    .document(new InputFile(zipFile))
                    .caption(messageService.getMessage("task.completed.full_processing_caption", task.getLanguageCode()))
                    .build();
            telegramClient.execute(sendDocument);

            task.setStatus(TaskStatus.COMPLETED);
            log.info("[ZIP] Completed full processing for video: {}", task.getVideoId());

        } catch (Exception e) {
            log.error("[ZIP] Error in full processing for video: {}", task.getVideoId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Error: " + e.getMessage());
            sendMessage(task.getChatId(),
                    messageService.getMessage("common.error", task.getLanguageCode()) + e.getMessage());
        } finally {
            // Step 7: Cleanup
            if (tempDir != null) {
                try (Stream<Path> walk = Files.walk(tempDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                    log.debug("[ZIP] Cleaned up temporary directory: {}", tempDir);
                } catch (IOException e) {
                    log.warn("[ZIP] Failed to clean up temp dir: {}", e.getMessage());
                }
            }
        }
    }

    private void addToZip(File file, ZipOutputStream zos) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zos.putNextEntry(zipEntry);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) >= 0) {
                zos.write(buffer, 0, length);
            }
            zos.closeEntry();
        }
    }

    private Video saveTranscriptionResult(DownloadTask task, String transcription, String detectedLanguage) {
        try {
            // Check if a video record already exists for this video
            Optional<Video> existingVideo = videoRepository.findByVideoId(task.getVideoId());

            Video video;
            if (existingVideo.isPresent()) {
                // Update existing video record
                video = existingVideo.get();
                video.setTranscriptionText(transcription);
                video.setOriginalLanguage(detectedLanguage);
                video.setWordCount(transcription.split("\\s+").length); // Update word count
                video.setTranscriptionStatus("COMPLETED");
                log.info("Updated existing video record with transcription for video: {}", task.getVideoId());
            } else {
                // Create new video record
                video = new Video();
                video.setVideoId(task.getVideoId());
                video.setTranscriptionText(transcription);
                video.setOriginalLanguage(detectedLanguage);
                video.setWordCount(transcription.split("\\s+").length); // Simple word count
                video.setTranscriptionStatus("COMPLETED");

                // Find the associated request to get additional info
                Optional<Request> requestOpt = findRequestByVideoIdSafely(task.getVideoId());

                if (requestOpt.isPresent()) {
                    Request request = requestOpt.get();
                    // You could populate more fields from the request if needed
                    if (request.getChannel() != null) {
                        video.setChannelId(request.getChannel().getId()); // Link to the channel if available
                    }
                }
                log.info("Created new video record with transcription for video: {}", task.getVideoId());
            }

            return videoRepository.save(video);
        } catch (Exception e) {
            log.error("Failed to save video transcription for video: {}", task.getVideoId(), e);
            return null;
        }
    }

    private Optional<Request> findRequestByVideoIdSafely(String videoId) {
        try {
            return requestRepository.findByVideoId(videoId);
        } catch (Exception e) {
            // If there are multiple results, try to get the most recent one
            log.warn("Multiple requests found for videoId: {}, getting the first one", videoId);
            List<Request> requests = requestRepository.findAllByVideoId(videoId);
            return requests.stream().findFirst();
        }
    }

    private void sendTranscriptionToUser(Long chatId, String transcription, String videoId, String languageCode) {
        try {
            // If transcription is too long for a single message, we'll send it in parts
            if (transcription.length() > 4000) {
                // Split into chunks of approximately 4000 characters
                String[] parts = splitString(transcription, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📄 %s (%d/%d):\n\n%s",
                            messageService.getMessage("bot.button.text", languageCode),
                            i + 1, parts.length, parts[i]);
                    sendMessage(chatId, part);
                    // Small delay between messages to avoid rate limiting
                    Thread.sleep(1000);
                }
            } else {
                String message = "🎙️ " + messageService.getMessage("bot.button.text", languageCode) + " " + videoId
                        + ":\n\n" + transcription;
                sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending transcription to user", e);
        } catch (Exception e) {
            log.error("Failed to send transcription to user", e);
            sendMessage(chatId,
                    messageService.getMessage("common.error", languageCode) + " Failed to send transcription.");
        }
    }

    private String[] splitString(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return new String[] { text };
        }

        java.util.List<String> parts = new java.util.ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder currentPart = new StringBuilder();
        for (String sentence : sentences) {
            if (currentPart.length() + sentence.length() > maxLength) {
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString().trim());
                    currentPart = new StringBuilder();
                }

                // If a single sentence is longer than max length, split by words
                if (sentence.length() > maxLength) {
                    String[] words = sentence.split("\\s+");
                    StringBuilder temp = new StringBuilder();

                    for (String word : words) {
                        if (temp.length() + word.length() > maxLength) {
                            parts.add(temp.toString().trim());
                            temp = new StringBuilder(word + " ");
                        } else {
                            temp.append(word).append(" ");
                        }
                    }

                    if (temp.length() > 0) {
                        parts.add(temp.toString().trim());
                    }
                } else {
                    currentPart.append(sentence).append(" ");
                }
            } else {
                currentPart.append(sentence).append(" ");
            }
        }

        if (currentPart.length() > 0) {
            parts.add(currentPart.toString().trim());
        }

        return parts.toArray(new String[0]);
    }

    private void sendContent(Long chatId, File file, String type, String languageCode) {
        String caption = type.equals("VIDEO") ? messageService.getMessage("task.completed.video", languageCode)
                : messageService.getMessage("task.completed.audio", languageCode);

        SendDocument sendDocument = SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(file))
                .caption(caption)
                .build();
        try {
            telegramClient.execute(sendDocument);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends a donation menu with multiple amount options after successful task
     * completion.
     */
    private void sendDonationMenu(long chatId, String languageCode) {
        try {
            // Create inline keyboard with donation options
            var keyboard = new java.util.ArrayList<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow>();

            // Row 1: 10, 50, 100 stars
            var row1 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
            row1.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text("⭐ 10")
                    .callbackData("donate:10")
                    .build());
            row1.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text("⭐ 50")
                    .callbackData("donate:50")
                    .build());
            row1.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text("⭐ 100")
                    .callbackData("donate:100")
                    .build());
            keyboard.add(row1);

            // Row 2: Custom amount
            var row2 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
            row2.add(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                    .text(messageService.getMessage("donation.custom", languageCode))
                    .callbackData("donate:custom")
                    .build());
            keyboard.add(row2);

            var markup = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                    .keyboard(keyboard)
                    .build();

            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(messageService.getMessage("donation.choose_amount", languageCode))
                    .replyMarkup(markup)
                    .build();

            telegramClient.execute(message);
            log.info("Donation menu sent to chat {}", chatId);
        } catch (TelegramApiException e) {
            log.warn("Failed to send donation menu: {}", e.getMessage());
        }
    }

    /**
     * Sends a donation invoice for Telegram Stars with specified amount.
     * Called from bot when user selects an amount.
     */
    public void sendDonationInvoice(long chatId, int amount, String languageCode) {
        try {
            String label = "⭐ " + amount + " Stars";
            SendInvoice sendInvoice = SendInvoice.builder()
                    .chatId(chatId)
                    .title(messageService.getMessage("donation.title", languageCode))
                    .description(messageService.getMessage("donation.description", languageCode))
                    .payload("donation_" + amount + "_stars_" + System.currentTimeMillis())
                    .currency("XTR")
                    .providerToken("")
                    .price(new LabeledPrice(label, amount))
                    .startParameter("donate")
                    .build();

            telegramClient.execute(sendInvoice);
            log.info("Donation invoice for {} stars sent to chat {}", amount, chatId);
        } catch (TelegramApiException e) {
            log.warn("Failed to send donation invoice: {}", e.getMessage());
        }
    }
}
