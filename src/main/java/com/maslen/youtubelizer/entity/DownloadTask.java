package com.maslen.youtubelizer.entity;

import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "download_tasks", uniqueConstraints = @UniqueConstraint(columnNames = { "video_id", "type" }))
@Data
public class DownloadTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "video_id", nullable = false, length = 100)
    private String videoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TaskStatus status;

    @Column(name = "error_message", length = 2048)
    private String errorMessage;

    @Column(name = "language_code", length = 10)
    private String languageCode;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
