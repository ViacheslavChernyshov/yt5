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
     * Validates if the given URL is a valid YouTube video or shorts link
     */
    public boolean isValidYouTubeLink(String url) {
        return isYouTubeVideoLink(url) || isYouTubeShortsLink(url);
    }

    /**
     * Checks if the URL is a YouTube video link
     */
    public boolean isYouTubeVideoLink(String url) {
        return YOUTUBE_VIDEO_PATTERN.matcher(url).find();
    }

    /**
     * Checks if the URL is a YouTube shorts link
     */
    public boolean isYouTubeShortsLink(String url) {
        return YOUTUBE_SHORTS_PATTERN.matcher(url).find();
    }

    /**
     * Extracts video ID from YouTube URL
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
     * Gets video info using yt-dlp
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

        // Read error stream separately to prevent warnings from interfering with JSON
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
                    "yt-dlp command failed with exit code: " + exitCode + ", error: " + errorOutput.toString());
        }

        // Clean up the output to extract only the JSON part
        String outputStr = output.toString().trim();

        // Find the JSON object in the output (skip any warnings at the beginning)
        int jsonStart = outputStr.indexOf('{');
        if (jsonStart != -1) {
            outputStr = outputStr.substring(jsonStart);
        }

        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readTree(outputStr);
    }

    /**
     * Creates a request record in the database
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
     * Processes a YouTube URL and extracts channel information
     */
    public Channel processYouTubeUrl(String url) throws Exception {
        String videoId = extractVideoId(url);
        if (videoId == null) {
            throw new IllegalArgumentException("Invalid YouTube URL");
        }

        JsonNode videoInfo = getVideoInfo(videoId);

        String channelId = videoInfo.get("channel_id").asText();
        String channelTitle = videoInfo.has("channel") ? videoInfo.get("channel").asText() : null;
        String channelUrl = videoInfo.has("channel_url") ? videoInfo.get("channel_url").asText() : null;
        String description = videoInfo.has("description") ? videoInfo.get("description").asText() : null;

        // Try to get subscriber count and video count if available
        JsonNode followerCountNode = videoInfo.get("channel_follower_count");
        JsonNode videoCountNode = videoInfo.get("channel_video_count");

        Long subscriberCount = followerCountNode != null ? followerCountNode.asLong() : null;
        Integer videoCount = videoCountNode != null ? videoCountNode.asInt() : null;

        // Check if channel already exists in database
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