package com.maslen.youtubelizer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "channels")
@Data
public class Channel {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "youtube_channel_id", unique = true, nullable = false, length = 100)
    private String youtubeChannelId;
    
    @Column(name = "channel_title", length = 500)
    private String channelTitle;
    
    @Column(name = "channel_url", length = 1000)
    private String channelUrl;
    
    @Column(name = "subscriber_count")
    private Long subscriberCount;
    
    @Column(name = "video_count")
    private Integer videoCount;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}