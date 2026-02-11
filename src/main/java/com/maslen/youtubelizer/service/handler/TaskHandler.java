package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskType;

public interface TaskHandler {
    boolean canHandle(TaskType type);

    void handle(DownloadTask task);
}
