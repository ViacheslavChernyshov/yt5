package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Video;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.repository.VideoRepository;
import com.maslen.youtubelizer.service.MessageService;
import com.maslen.youtubelizer.service.TelegramNotificationService;
import com.maslen.youtubelizer.service.TranscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class SpeechRecognitionTaskHandler extends BaseTaskHandler {

    private final TranscriptionService transcriptionService;
    private final VideoRepository videoRepository;

    public SpeechRecognitionTaskHandler(DownloadTaskRepository downloadTaskRepository,
            TelegramNotificationService notificationService,
            MessageService messageService,
            TranscriptionService transcriptionService,
            VideoRepository videoRepository) {
        super(downloadTaskRepository, notificationService, messageService);
        this.transcriptionService = transcriptionService;
        this.videoRepository = videoRepository;
    }

    @Override
    public boolean canHandle(TaskType type) {
        return type == TaskType.SPEECH_RECOGNITION;
    }

    @Override
    public void handle(DownloadTask task) {
        try {
            // Check cache
            Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
            if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                    && !videoOpt.get().getTranscriptionText().isEmpty()) {

                log.info("Found cached transcription for video: {}", task.getVideoId());
                sendTranscriptionToUser(task.getChatId(), videoOpt.get().getTranscriptionText(), task.getVideoId(),
                        task.getLanguageCode());
                updateTaskStatus(task, TaskStatus.COMPLETED);
                return;
            }

            // Perform transcription
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            Video video = transcriptionService.performTranscription(task, url);

            if (video != null && video.getTranscriptionText() != null && !video.getTranscriptionText().isEmpty()) {
                sendTranscriptionToUser(task.getChatId(), video.getTranscriptionText(), task.getVideoId(),
                        task.getLanguageCode());
                updateTaskStatus(task, TaskStatus.COMPLETED);
            } else {
                failTask(task, "Transcription failed or returned empty result");
            }

        } catch (Exception e) {
            failTask(task, e.getMessage());
        }
    }

    private void sendTranscriptionToUser(Long chatId, String transcription, String videoId, String languageCode) {
        try {
            if (transcription.length() > 4000) {
                String[] parts = splitString(transcription, 4000);
                for (int i = 0; i < parts.length; i++) {
                    String part = String.format("📄 %s (%d/%d):\n\n%s",
                            messageService.getMessage("bot.button.text", languageCode),
                            i + 1, parts.length, parts[i]);
                    notificationService.sendMessage(chatId, part);
                    Thread.sleep(1000);
                }
            } else {
                String message = "🎙️ " + messageService.getMessage("bot.button.text", languageCode) + " " + videoId
                        + ":\n\n" + transcription;
                notificationService.sendMessage(chatId, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending transcription", e);
        } catch (Exception e) {
            log.error("Failed to send transcription", e);
            notificationService.sendMessage(chatId,
                    messageService.getMessage("common.error", languageCode) + " Failed to send transcription.");
        }
    }

    // Need splitString duplicated here or move to BaseTaskHandler?
    // It's used in TaskSchedulerService. Let's move it to BaseTaskHandler or a
    // StringUtils.
    // For now, I'll put it here to keep handler self-contained, or duplicates are
    // bad.
    // Ideally BaseTaskHandler or MessageService should have it.
    // I'll put it in BaseTaskHandler.
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
