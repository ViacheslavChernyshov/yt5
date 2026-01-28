package com.maslen.youtubelizer.repository;

import com.maslen.youtubelizer.entity.Request;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RequestRepository extends JpaRepository<Request, Long> {
    Optional<Request> findByVideoId(String videoId);
    List<Request> findAllByVideoId(String videoId);
}