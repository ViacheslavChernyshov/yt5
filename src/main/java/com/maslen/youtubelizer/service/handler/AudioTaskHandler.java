package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.service.MessageService;
import com.maslen.youtubelizer.service.TelegramNotificationService;
import com.maslen.youtubelizer.service.YtDlpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Paths;

@Slf4j
@Component
public class AudioTaskHandler extends BaseTaskHandler {

    private final YtDlpService ytDlpService;

    public AudioTaskHandler(DownloadTaskRepository downloadTaskRepository,
            TelegramNotificationService notificationService,
            MessageService messageService,
            YtDlpService ytDlpService) {
        super(downloadTaskRepository, notificationService, messageService);
        this.ytDlpService = ytDlpService;
    }

    @Override
    public boolean canHandle(TaskType type) {
        return type == TaskType.AUDIO;
    }

    @Override
    public void handle(DownloadTask task) {
        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            File file = ytDlpService.downloadAudio(url, Paths.get("downloads"), task.getVideoId());

            if (file != null && file.exists()) {
                notificationService.sendDocument(task.getChatId(), file,
                        messageService.getMessage("task.completed.audio", task.getLanguageCode()));
                updateTaskStatus(task, TaskStatus.COMPLETED);

                // Delete file after sending
                try {
                    file.delete();
                } catch (Exception e) {
                    log.error("Failed to delete audio file: {}", file.getAbsolutePath(), e);
                }
            } else {
                failTask(task, "Audio file not found after download");
            }
        } catch (Exception e) {
            failTask(task, e.getMessage());
        }
    }
}
