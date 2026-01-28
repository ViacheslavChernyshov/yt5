package com.maslen.youtubelizer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maslen.youtubelizer.entity.Channel;
import com.maslen.youtubelizer.entity.Request;
import com.maslen.youtubelizer.repository.ChannelRepository;
import com.maslen.youtubelizer.repository.RequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class YouTubeService {

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private RequestRepository requestRepository;

    private static final Pattern YOUTUBE_VIDEO_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/|v/)|youtu\\.be/)([\\w-]{11})");

    private static final Pattern YOUTUBE_SHORTS_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?youtube\\.com/shorts/([\\w-]{11})");

    /**
     * Проверяет, является ли URL валидной ссылкой на видео или шортс YouTube
     */
    public boolean isValidYouTubeLink(String url) {
        return isYouTubeVideoLink(url) || isYouTubeShortsLink(url);
    }

    /**
     * Проверяет, является ли URL ссылкой на видео YouTube
     */
    public boolean isYouTubeVideoLink(String url) {
        return YOUTUBE_VIDEO_PATTERN.matcher(url).find();
    }

    /**
     * Проверяет, является ли URL ссылкой на шортс YouTube
     */
    public boolean isYouTubeShortsLink(String url) {
        return YOUTUBE_SHORTS_PATTERN.matcher(url).find();
    }

    /**
     * Извлекает ID видео из URL YouTube
     */
    public String extractVideoId(String url) {
        Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }

        matcher = YOUTUBE_SHORTS_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Получает информацию о видео с помощью yt-dlp
     */
    public JsonNode getVideoInfo(String videoId) throws Exception {
        String[] command = {
                ytDlpService.getYtDlpPath(),
                "--extractor-args", "youtube:player_client=android",
                "--user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "--dump-json",
                "--no-warnings",
                "https://www.youtube.com/watch?v=" + videoId
        };

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // Чтение потока ошибок отдельно, чтобы предупреждения не мешали JSON
        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorOutput.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Команда yt-dlp завершилась с кодом: " + exitCode + ", ошибка: " + errorOutput.toString());
        }

        // Очистка вывода для извлечения только части JSON
        String outputStr = output.toString().trim();

        // Поиск JSON объекта в выводе (пропуск любых предупреждений в начале)
        int jsonStart = outputStr.indexOf('{');
        if (jsonStart != -1) {
            outputStr = outputStr.substring(jsonStart);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(outputStr);
    }

    /**
     * Создает запись запроса в базе данных
     */
    public Request createRequest(Long userId, String userName, String messageText, Boolean isValidLink,
            String youtubeUrl, String videoId, Channel channel) {
        Request request = new Request();
        request.setUserId(userId);
        request.setUserName(userName);
        request.setMessageText(messageText);
        request.setIsValidLink(isValidLink);
        request.setYoutubeUrl(youtubeUrl);
        request.setVideoId(videoId);
        request.setChannel(channel);

        return requestRepository.save(request);
    }

    /**
     * Обрабатывает URL YouTube и извлекает информацию о канале
     */
    public Channel processYouTubeUrl(String url) throws Exception {
        String videoId = extractVideoId(url);
        if (videoId == null) {
            throw new IllegalArgumentException("Невалидный URL YouTube");
        }

        JsonNode videoInfo = getVideoInfo(videoId);

        String channelId = videoInfo.get("channel_id").asText();
        String channelTitle = videoInfo.has("channel") ? videoInfo.get("channel").asText() : null;
        String channelUrl = videoInfo.has("channel_url") ? videoInfo.get("channel_url").asText() : null;
        String description = videoInfo.has("description") ? videoInfo.get("description").asText() : null;

        // Попытка получить количество подписчиков и видео, если доступно
        JsonNode followerCountNode = videoInfo.get("channel_follower_count");
        JsonNode videoCountNode = videoInfo.get("channel_video_count");

        Long subscriberCount = followerCountNode != null ? followerCountNode.asLong() : null;
        Integer videoCount = videoCountNode != null ? videoCountNode.asInt() : null;

        // Проверка, существует ли уже канал в базе данных
        return channelRepository.findByYoutubeChannelId(channelId)
                .orElseGet(() -> {
                    Channel newChannel = new Channel();
                    newChannel.setYoutubeChannelId(channelId);
                    newChannel.setChannelTitle(channelTitle);
                    newChannel.setChannelUrl(channelUrl);
                    newChannel.setSubscriberCount(subscriberCount);
                    newChannel.setVideoCount(videoCount);
                    newChannel.setDescription(description);

                    return channelRepository.save(newChannel);
                });
    }
}