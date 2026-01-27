package com.maslen.youtubelizer.repository;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DownloadTaskRepository extends JpaRepository<DownloadTask, Long> {
    Optional<DownloadTask> findTopByStatusOrderByCreatedAtAsc(TaskStatus status);
    
    java.util.List<DownloadTask> findByStatusAndUpdatedAtBefore(TaskStatus status, java.time.LocalDateTime dateTime);
}
