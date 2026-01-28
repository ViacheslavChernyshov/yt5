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
            log.warn("Error processing tasks, possibly because the download_tasks table is not yet created: {}",
                    e.getMessage());
            // This can happen during startup if Flyway migrations haven't completed yet
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
            log.warn("Error resetting stuck tasks, possibly because the download_tasks table is not yet created: {}",
                    e.getMessage());
            // This can happen during startup if Flyway migrations haven't completed yet
        }
    }

    private void processTask(DownloadTask task) {
        log.info("Processing task id: {} type: {}", task.getId(), task.getType());

        task.setStatus(TaskStatus.PROCESSING);
        downloadTaskRepository.save(task);

        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            File file = null;

            if (task.getType() == TaskType.VIDEO) {
                file = ytDlpService.downloadVideo(url, Paths.get("downloads"), task.getVideoId());
                if (file != null && file.exists()) {
                    sendContent(task.getChatId(), file, task.getType().name());
                    task.setStatus(TaskStatus.COMPLETED);

                    // Delete file after sending
                    try {
                        file.delete();
                    } catch (Exception e) {
                        log.error("Failed to delete file: {}", file.getAbsolutePath(), e);
                    }
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setErrorMessage("Video file not found after download");
                    sendMessage(task.getChatId(), "❌ Ошибка: видео не было скачано.");
                }
            } else if (task.getType() == TaskType.AUDIO) {
                file = ytDlpService.downloadAudio(url, Paths.get("downloads"), task.getVideoId());
                if (file != null && file.exists()) {
                    sendContent(task.getChatId(), file, task.getType().name());
                    task.setStatus(TaskStatus.COMPLETED);

                    // Delete file after sending
                    try {
                        file.delete();
                    } catch (Exception e) {
                        log.error("Failed to delete file: {}", file.getAbsolutePath(), e);
                    }
                } else {
                    task.setStatus(TaskStatus.FAILED);
                    task.setErrorMessage("Audio file not found after download");
                    sendMessage(task.getChatId(), "❌ Ошибка: аудио не было скачано.");
                }
            } else if (task.getType() == TaskType.SPEECH_RECOGNITION) {
                processSpeechRecognitionTask(task, url);
            } else if (task.getType() == TaskType.TEXT_NORMALIZATION) {
                processTextNormalizationTask(task);
            } else if (task.getType() == TaskType.FULL_PROCESSING_ZIP) {
                processFullProcessingZipTask(task);
            } else {
                throw new IllegalArgumentException("Unknown task type: " + task.getType());
            }

        } catch (Exception e) {
            log.error("Error processing task {}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            sendMessage(task.getChatId(), "❌ Произошла ошибка при обработке запроса: " + e.getMessage());
        }

        downloadTaskRepository.save(task);
    }

    private void processSpeechRecognitionTask(DownloadTask task, String url) throws IOException, InterruptedException {
        // Step 1: Check if we already have transcription in DB
        Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
        if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                && !videoOpt.get().getTranscriptionText().isEmpty()) {

            log.info("Found cached transcription for video: {}", task.getVideoId());
            sendTranscriptionToUser(task.getChatId(), videoOpt.get().getTranscriptionText(), task.getVideoId());
            task.setStatus(TaskStatus.COMPLETED);
            return;
        }

        // Step 2: If not found, perform transcription
        Video video = performTranscription(task, url);

        if (video != null) {
            // Step 3: Send transcription to user
            sendTranscriptionToUser(task.getChatId(), video.getTranscriptionText(), task.getVideoId());
            task.setStatus(TaskStatus.COMPLETED);
        }
    }

    private void processTextNormalizationTask(DownloadTask task) throws IOException, InterruptedException {
        String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
        log.info("[TEXT_NORMALIZATION] Starting normalization for video: {}", task.getVideoId());

        // Step 1: Check if we already have normalized text in DB
        Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());

        if (videoOpt.isPresent() && videoOpt.get().getNormalizedText() != null
                && !videoOpt.get().getNormalizedText().isEmpty()) {

            log.info("Found cached normalized text for video: {}", task.getVideoId());
            sendNormalizedTextToUser(task.getChatId(), videoOpt.get().getNormalizedText(), task.getVideoId());
            task.setStatus(TaskStatus.COMPLETED);
            return;
        }

        Video video;
        // Step 2: Check if we have transcription to normalize
        if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                && !videoOpt.get().getTranscriptionText().isEmpty()) {
            video = videoOpt.get();
        } else {
            // Step 3: No transcription? We need to download and transcribe first!
            sendMessage(task.getChatId(), "⏳ Транскрипция не найдена. Начинаю процесс скачивания и распознавания...");
            video = performTranscription(task, url);
            if (video == null)
                return; // Error happened in performTranscription
        }

        try {
            // Step 4: Normalize the text using LLM
            String transcription = video.getTranscriptionText();
            String language = video.getOriginalLanguage();

            String normalizedText = llamaService.normalizeText(transcription, language);

            if (normalizedText == null || normalizedText.trim().isEmpty()) {
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage("Normalization returned empty result");
                sendMessage(task.getChatId(), "❌ Ошибка: нейросеть вернула пустой результат.");
                return;
            }

            // Step 5: Save normalized text to database
            video.setNormalizedText(normalizedText);
            videoRepository.save(video);
            log.info("[TEXT_NORMALIZATION] Saved normalized text for video: {}", task.getVideoId());

            // Step 6: Send normalized text to user
            sendNormalizedTextToUser(task.getChatId(), normalizedText, task.getVideoId());

            // Step 7: Mark task as completed
            task.setStatus(TaskStatus.COMPLETED);
            log.info("[TEXT_NORMALIZATION] Completed normalization for video: {}", task.getVideoId());

        } catch (Exception e) {
            log.error("[TEXT_NORMALIZATION] Error normalizing text for video: {}", task.getVideoId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Normalization error: " + e.getMessage());
            sendMessage(task.getChatId(), "❌ Ошибка при нормализации текста: " + e.getMessage());
        }
    }

    /**
     * Helper method to download audio, transcribe it, and save to DB.
     * Returns the updated Video entity or null if failed.
     */
    private Video performTranscription(DownloadTask task, String url) throws IOException, InterruptedException {
        // Step 1: Download audio temporarily
        File audioFile = ytDlpService.downloadAudio(url, Paths.get("temp"), "temp_" + task.getVideoId());

        if (audioFile == null || !audioFile.exists()) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Audio file not found after download");
            sendMessage(task.getChatId(), "❌ Ошибка: не удалось скачать аудио для распознавания речи.");
            return null;
        }

        try {
            return transcribeFile(task, audioFile);
        } finally {
            // Step 4: Clean up temporary audio file
            try {
                if (audioFile.exists()) {
                    audioFile.delete();
                    log.debug("Deleted temporary audio file: {}", audioFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("Failed to delete temporary audio file: {}", audioFile.getAbsolutePath(), e);
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

    private void sendNormalizedTextToUser(Long chatId, String normalizedText, String videoId) {
        try {
            // If text is too long for a single message, split it
            if (normalizedText.length() > 4000) {
                String[] parts = splitString(normalizedText, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📝 Нормализованный текст (часть %d/%d):\n\n%s",
                            i + 1, parts.length, parts[i]);
                    sendMessage(chatId, part);
                    Thread.sleep(1000); // Small delay between messages
                }
            } else {
                String message = "✨ Нормализованный текст для видео " + videoId + ":\n\n" + normalizedText;
                sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending normalized text to user", e);
        } catch (Exception e) {
            log.error("Failed to send normalized text to user", e);
            sendMessage(chatId, "⚠️ Текст нормализован, но возникла ошибка при отправке.");
        }
    }

    private void processFullProcessingZipTask(DownloadTask task) {
        log.info("[ZIP] Starting full processing for video: {}", task.getVideoId());
        Path tempDir = null;
        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            tempDir = Files.createTempDirectory("zip_" + task.getVideoId());

            // Step 1: Download Media (ALWAYS)
            sendMessage(task.getChatId(), "📥 Скачиваю видео и аудио...");
            File videoFile = ytDlpService.downloadVideo(url, tempDir, "video");
            File audioFile = ytDlpService.downloadAudio(url, tempDir, "audio");

            if (videoFile == null || !videoFile.exists() || audioFile == null || !audioFile.exists()) {
                throw new IOException("Failed to download media files");
            }

            // Step 2: Get Transcription
            sendMessage(task.getChatId(), "🎙️ Проверяю/генерирую транскрипцию...");
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
            sendMessage(task.getChatId(), "📝 Проверяю/генерирую нормализацию...");
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
            sendMessage(task.getChatId(), "📦 Упаковываю архив...");
            File zipFile = tempDir.resolve("content.zip").toFile();
            try (FileOutputStream fos = new FileOutputStream(zipFile);
                    ZipOutputStream zos = new ZipOutputStream(fos)) {

                addToZip(videoFile, zos);
                addToZip(audioFile, zos);
                addToZip(transcriptionFile, zos);
                addToZip(normalizedFile, zos);
            }

            // Step 6: Send ZIP
            sendMessage(task.getChatId(), "🚀 Отправляю архив пользователю...");
            SendDocument sendDocument = SendDocument.builder()
                    .chatId(task.getChatId())
                    .document(new InputFile(zipFile))
                    .caption(
                            "📦 Ваш полный архив готов!\n\nВнутри:\n- Видео (MP4)\n- Аудио (MP3)\n- Транскрипция (TXT)\n- Нормализованный текст (TXT)")
                    .build();
            telegramClient.execute(sendDocument);

            task.setStatus(TaskStatus.COMPLETED);
            log.info("[ZIP] Completed full processing for video: {}", task.getVideoId());

        } catch (Exception e) {
            log.error("[ZIP] Error in full processing for video: {}", task.getVideoId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("Error: " + e.getMessage());
            sendMessage(task.getChatId(), "❌ Ошибка при создании архива: " + e.getMessage());
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

    private void sendTranscriptionToUser(Long chatId, String transcription, String videoId) {
        try {
            // If transcription is too long for a single message, we'll send it in parts
            if (transcription.length() > 4000) {
                // Split into chunks of approximately 4000 characters
                String[] parts = splitString(transcription, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📄 Транскрипция (часть %d/%d):\n\n%s",
                            i + 1, parts.length, parts[i]);
                    sendMessage(chatId, part);
                    // Small delay between messages to avoid rate limiting
                    Thread.sleep(1000);
                }
            } else {
                String message = "🎙️ Результат распознавания речи для видео " + videoId + ":\n\n" + transcription;
                sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending transcription to user", e);
        } catch (Exception e) {
            log.error("Failed to send transcription to user", e);
            sendMessage(chatId, "⚠️ Текст распознан, но возникла ошибка при отправке.");
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

    private void sendContent(Long chatId, File file, String type) {
        String caption = type.equals("VIDEO") ? "📹 Ваше видео готово!" : "🎧 Ваше аудио готово!";

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
}
