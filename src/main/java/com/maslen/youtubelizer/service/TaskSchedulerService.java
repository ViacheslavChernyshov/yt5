package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
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
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class TaskSchedulerService {

    @Autowired
    private DownloadTaskRepository downloadTaskRepository;

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private TelegramClient telegramClient;

    @Scheduled(fixedDelay = 1000)
    public void processNextTask() {
        resetStuckTasks(); // Сбрасываем зависшие задачи перед началом новой
        
        Optional<DownloadTask> taskOpt = downloadTaskRepository.findTopByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);

        if (taskOpt.isPresent()) {
            DownloadTask task = taskOpt.get();
            processTask(task);
        }
    }
    
    /**
     * Сброс задач, которые находятся в состоянии PROCESSING более 1 часа
     */
    private void resetStuckTasks() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        List<DownloadTask> stuckTasks = downloadTaskRepository.findByStatusAndUpdatedAtBefore(TaskStatus.PROCESSING, oneHourAgo);
        
        if (!stuckTasks.isEmpty()) {
            log.info("Найдено {} зависших задач, сбрасываем их в статус PENDING", stuckTasks.size());
            
            for (DownloadTask task : stuckTasks) {
                log.warn("Сброс зависшей задачи id: {}, videoId: {}, последнее обновление: {}",
                    task.getId(), task.getVideoId(), task.getUpdatedAt());
                
                task.setStatus(TaskStatus.PENDING); // Возвращаем в начальное состояние
                downloadTaskRepository.save(task);
            }
        }
    }

    private void processTask(DownloadTask task) {
        log.info("Processing task id: {} type: {}", task.getId(), task.getType());

        task.setStatus(TaskStatus.PROCESSING);
        downloadTaskRepository.save(task);

        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            File file;

            if (task.getType() == TaskType.VIDEO) {
                file = ytDlpService.downloadVideo(url, Paths.get("downloads"), task.getVideoId());
            } else if (task.getType() == TaskType.AUDIO) {
                file = ytDlpService.downloadAudio(url, Paths.get("downloads"), task.getVideoId());
            } else {
                throw new IllegalArgumentException("Unknown task type: " + task.getType());
            }

            if (file != null && file.exists()) {
                sendContent(task.getChatId(), file, task.getType().name());
                task.setStatus(TaskStatus.COMPLETED);

                // Delete file after sending
                // Note: In a real production system we might want to keep it or have a separate
                // cleanup policy
                try {
                    file.delete();
                } catch (Exception e) {
                    log.error("Failed to delete file: {}", file.getAbsolutePath(), e);
                }
            } else {
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage("File not found after download");
                sendMessage(task.getChatId(), "❌ Ошибка: файл не был скачан.");
            }

        } catch (Exception e) {
            log.error("Error processing task {}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            sendMessage(task.getChatId(), "❌ Произошла ошибка при обработке запроса: " + e.getMessage());
        }

        downloadTaskRepository.save(task);
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
