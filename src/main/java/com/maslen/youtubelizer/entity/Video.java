package com.maslen.youtubelizer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "videos")
@Data
public class Video {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "video_id", unique = true, nullable = false, length = 50)
    private String videoId;
    
    @Column(name = "video_title", length = 1000)
    private String videoTitle;
    
    @Column(name = "video_url", length = 1000)
    private String videoUrl;
    
    @Column(name = "channel_id")
    private Long channelId;
    
    @Column(name = "duration_seconds")
    private Integer durationSeconds;
    
    @Column(name = "view_count")
    private Long viewCount;
    
    @Column(name = "like_count")
    private Long likeCount;
    
    @Column(name = "upload_date")
    private LocalDateTime uploadDate;
    
    @Column(name = "original_language", length = 10)
    private String originalLanguage;
    
    @Column(name = "transcription_text", columnDefinition = "TEXT")
    private String transcriptionText;
    
    @Column(name = "normalized_text", columnDefinition = "TEXT")
    private String normalizedText;
    
    @Column(name = "word_count")
    private Integer wordCount;
    
    @Column(name = "transcription_status", length = 50)
    private String transcriptionStatus;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}