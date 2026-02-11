package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Request;
import com.maslen.youtubelizer.entity.Video;
import com.maslen.youtubelizer.model.TranscriptionResult;
import com.maslen.youtubelizer.repository.RequestRepository;
import com.maslen.youtubelizer.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final YtDlpService ytDlpService;
    private final WhisperService whisperService;
    private final VideoRepository videoRepository;
    private final RequestRepository requestRepository;
    private final TelegramNotificationService notificationService;
    private final MessageService messageService;

    /**
     * Выполняет транскрипцию для задачи: скачивает аудио (если нужно),
     * транскрибирует, сохраняет.
     */
    public Video performTranscription(DownloadTask task, String url) throws IOException, InterruptedException {
        // Шаг 1: Временное скачивание аудио
        File audioFile = ytDlpService.downloadAudio(url, Paths.get("temp"), "temp_" + task.getVideoId());

        if (audioFile == null || !audioFile.exists()) {
            // Task status update should be handled by the caller or exception thrown?
            // Here we just return null or throw exception.
            // Caller expects Video or null.
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("error.download_failed", task.getLanguageCode()));
            throw new IOException("Audio file not found after download");
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

    /**
     * Transcribe any audio file and save result related to the task.
     */
    public Video transcribeFile(DownloadTask task, File audioFile) throws IOException, InterruptedException {
        // Step 2: Transcribe the audio using Whisper with language detection
        log.info("Starting transcription with language detection for video: {}", task.getVideoId());
        TranscriptionResult result = whisperService.transcribeWithLanguage(audioFile);
        String transcription = result.text();
        String detectedLanguage = result.language();

        // Clean and normalize transcription text
        transcription = normalizeTranscriptionText(transcription);

        // Step 3: Save transcription result to database and return the Video entity
        Video video = saveTranscriptionResult(task, transcription, detectedLanguage);

        // Step 4: Save transcription to file
        if (video != null) {
            saveTranscriptionToFile(task.getVideoId(), transcription);
        }

        return video;
    }

    private String normalizeTranscriptionText(String transcription) {
        if (transcription == null || transcription.isEmpty()) {
            return transcription;
        }
        // Remove excessive whitespace
        return transcription.replaceAll("\\s+", " ").trim();
    }

    private Video saveTranscriptionResult(DownloadTask task, String transcription, String detectedLanguage) {
        try {
            Optional<Video> existingVideo = videoRepository.findByVideoId(task.getVideoId());

            Video video;
            if (existingVideo.isPresent()) {
                video = existingVideo.get();
                video.setTranscriptionText(transcription);
                video.setOriginalLanguage(detectedLanguage);
                video.setWordCount(transcription.split("\\s+").length);
                video.setTranscriptionStatus("COMPLETED");
                log.info("Updated existing video record with transcription for video: {}", task.getVideoId());
            } else {
                video = new Video();
                video.setVideoId(task.getVideoId());
                video.setTranscriptionText(transcription);
                video.setOriginalLanguage(detectedLanguage);
                video.setWordCount(transcription.split("\\s+").length);
                video.setTranscriptionStatus("COMPLETED");

                Optional<Request> requestOpt = findRequestByVideoIdSafely(task.getVideoId());
                if (requestOpt.isPresent()) {
                    Request request = requestOpt.get();
                    if (request.getChannel() != null) {
                        video.setChannel(request.getChannel());
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
            log.warn("Multiple requests found for videoId: {}, getting the first one", videoId);
            List<Request> requests = requestRepository.findAllByVideoId(videoId);
            return requests.stream().findFirst();
        }
    }

    private void saveTranscriptionToFile(String videoId, String transcription) {
        try {
            Path downloadsDir = Paths.get("downloads");
            Files.createDirectories(downloadsDir);

            Path transcriptionFile = downloadsDir.resolve("transcription_" + videoId + ".txt");
            Files.writeString(transcriptionFile, transcription);
            log.info("Transcription saved to file: {}", transcriptionFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to save transcription to file for video {}: {}", videoId, e.getMessage());
        }
    }
}
