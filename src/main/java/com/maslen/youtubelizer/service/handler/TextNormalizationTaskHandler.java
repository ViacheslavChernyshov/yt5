package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Video;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.repository.VideoRepository;
import com.maslen.youtubelizer.service.MessageService;
import com.maslen.youtubelizer.service.NormalizationService;
import com.maslen.youtubelizer.service.TelegramNotificationService;
import com.maslen.youtubelizer.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TextNormalizationTaskHandler extends BaseTaskHandler {

    private final NormalizationService normalizationService;
    private final TranscriptionService transcriptionService;
    private final VideoRepository videoRepository;

    public TextNormalizationTaskHandler(DownloadTaskRepository downloadTaskRepository,
            TelegramNotificationService notificationService,
            MessageService messageService,
            NormalizationService normalizationService,
            TranscriptionService transcriptionService,
            VideoRepository videoRepository) {
        super(downloadTaskRepository, notificationService, messageService);
        this.normalizationService = normalizationService;
        this.transcriptionService = transcriptionService;
        this.videoRepository = videoRepository;
    }

    @Override
    public boolean canHandle(TaskType type) {
        return type == TaskType.TEXT_NORMALIZATION;
    }

    @Override
    public void handle(DownloadTask task) {
        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            log.info("[TEXT_NORMALIZATION] Starting normalization for video: {}", task.getVideoId());

            // Check cache
            Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
            if (videoOpt.isPresent() && videoOpt.get().getNormalizedText() != null
                    && !videoOpt.get().getNormalizedText().isEmpty()) {

                log.info("Found cached normalized text for video: {}", task.getVideoId());
                sendNormalizedTextToUser(task.getChatId(), videoOpt.get().getNormalizedText(), task.getVideoId(),
                        task.getLanguageCode());
                updateTaskStatus(task, TaskStatus.COMPLETED);
                return;
            }

            Video video;
            // Check for transcription
            if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                    && !videoOpt.get().getTranscriptionText().isEmpty()) {
                video = videoOpt.get();
            } else {
                notificationService.sendMessage(task.getChatId(),
                        messageService.getMessage("common.transcribing", task.getLanguageCode()));
                video = transcriptionService.performTranscription(task, url);
                if (video == null) {
                    return; // Error already handled in performTranscription
                }
            }

            // Normalize
            String transcription = video.getTranscriptionText();
            String language = video.getOriginalLanguage();

            String normalizedText = normalizationService.normalizeText(transcription, language);

            if (normalizedText == null) {
                failTask(task, "Normalization returned empty result");
                return;
            }

            // Save to DB
            video.setNormalizedText(normalizedText);
            videoRepository.save(video);
            log.info("[TEXT_NORMALIZATION] Saved normalized text for video: {}", task.getVideoId());

            // Save to file
            normalizationService.saveNormalizedTextToFile(task.getVideoId(), normalizedText);

            // Send to user
            sendNormalizedTextToUser(task.getChatId(), normalizedText, task.getVideoId(), task.getLanguageCode());
            updateTaskStatus(task, TaskStatus.COMPLETED);

        } catch (Exception e) {
            failTask(task, e.getMessage());
        }
    }

    private void sendNormalizedTextToUser(Long chatId, String normalizedText, String videoId, String languageCode) {
        try {
            if (normalizedText.length() > 4000) {
                String[] parts = splitString(normalizedText, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📝 %s (%d/%d):\n\n%s",
                            messageService.getMessage("common.normalizing", languageCode),
                            i + 1, parts.length, parts[i]);
                    notificationService.sendMessage(chatId, part);
                    Thread.sleep(1000);
                }
            } else {
                String message = "✨ " + messageService.getMessage("common.normalizing", languageCode) + " " + videoId
                        + ":\n\n" + normalizedText;
                notificationService.sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending normalized text", e);
        } catch (Exception e) {
            log.error("Failed to send normalized text", e);
            notificationService.sendMessage(chatId,
                    messageService.getMessage("common.error", languageCode) + " Failed to send text.");
        }
    }

    // Duplicated splitString again. I should move it to BaseTaskHandler.
    private String[] splitString(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return new String[] { text };
        }

        List<String> parts = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");

        StringBuilder currentPart = new StringBuilder();
        for (String sentence : sentences) {
            if (currentPart.length() + sentence.length() > maxLength) {
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString().trim());
                    currentPart = new StringBuilder();
                }
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
}
