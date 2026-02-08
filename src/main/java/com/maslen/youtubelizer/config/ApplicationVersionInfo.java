package com.maslen.youtubelizer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Логирует информацию о версии приложения при старте
 * Использует Git информацию через git-commit-id-maven-plugin
 */
@Slf4j
@Component
public class ApplicationVersionInfo {

    @Value("${spring.application.name:youtubelizer}")
    private String appName;

    @PostConstruct
    public void logApplicationInfo() {
        try {
            // Пытаемся загрузить информацию из git.properties
            Properties gitProperties = loadGitProperties();
            
            String version = getVersion(gitProperties);
            String branch = gitProperties.getProperty("git.branch", "unknown");
            String commitId = gitProperties.getProperty("git.commit.id.abbrev", "unknown");
            String commitTime = gitProperties.getProperty("git.commit.time", "unknown");
            String buildTime = gitProperties.getProperty("git.build.time", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            String javaVersion = System.getProperty("java.version");
            String osName = System.getProperty("os.name");
            String osVersion = System.getProperty("os.version");

            // Выводим красивый заголовок
            log.info("");
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║           {} started successfully", padCenter(appName.toUpperCase(), 54));
            log.info("╠════════════════════════════════════════════════════════════╣");
            log.info("║ Version:        {}", padRight(version, 48));
            log.info("║ Git Branch:     {}", padRight(branch, 48));
            log.info("║ Git Commit:     {}", padRight(commitId, 48));
            log.info("║ Commit Time:    {}", padRight(commitTime, 48));
            log.info("║ Build Time:     {}", padRight(buildTime, 48));
            log.info("╠════════════════════════════════════════════════════════════╣");
            log.info("║ Java Version:   {}", padRight(javaVersion, 48));
            log.info("║ OS:             {}", padRight(osName + " " + osVersion, 48));
            log.info("╚════════════════════════════════════════════════════════════╝");
            log.info("");
        } catch (Exception e) {
            log.warn("Не удалось загрузить информацию о версии: {}", e.getMessage());
            log.info("Application {} initialized", appName);
        }
    }

    private Properties loadGitProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = ApplicationVersionInfo.class.getResourceAsStream("/git.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        return properties;
    }

    private String getVersion(Properties gitProperties) {
        String tags = gitProperties.getProperty("git.tags", "");
        if (tags != null && !tags.isEmpty()) {
            // Если есть tag, используем его (например: v0.1.0)
            return tags.split(",")[0].trim();
        }
        
        String branch = gitProperties.getProperty("git.branch", "develop");
        String commitId = gitProperties.getProperty("git.commit.id.abbrev", "unknown");
        
        // Версия формата: branch-commitId (например: develop-a1b2c3d)
        return String.format("%s-%s", branch, commitId);
    }

    private String padCenter(String text, int totalWidth) {
        if (text.length() >= totalWidth) {
            return text;
        }
        int leftPad = (totalWidth - text.length()) / 2;
        int rightPad = totalWidth - text.length() - leftPad;
        return " ".repeat(Math.max(0, leftPad)) + text + " ".repeat(Math.max(0, rightPad));
    }

    private String padRight(String text, int totalWidth) {
        if (text.length() >= totalWidth) {
            return text.substring(0, totalWidth);
        }
        return text + " ".repeat(totalWidth - text.length()) + " ║";
    }
}
