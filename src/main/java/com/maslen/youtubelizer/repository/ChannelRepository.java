package com.maslen.youtubelizer.repository;

import com.maslen.youtubelizer.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findByYoutubeChannelId(String youtubeChannelId);
}