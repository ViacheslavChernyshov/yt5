package com.maslen.youtubelizer.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "requests")
@Data
public class Request {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "user_name")
    private String userName;
    
    @Column(name = "message_text", length = 1000)
    private String messageText;
    
    @Column(name = "is_valid_link")
    private Boolean isValidLink;
    
    @Column(name = "youtube_url", length = 1000)
    private String youtubeUrl;
    
    @Column(name = "video_id", length = 50)
    private String videoId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}