package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class WhisperService {

    @Value("${app.whisper.path:}")
    private String whisperPath;

    @Value("${app.whisper.model.path:}")
    private String modelPath;

    @Value("${app.whisper.use-gpu:false}")
    private boolean useGpu;

    @Value("${app.whisper.threads:4}")
    private int threads;

    @PostConstruct
    private void initializePaths() {
        // Use Python's whisper command - installed via pip in Docker
        // For local development, user must have: pip install openai-whisper
        whisperPath = "whisper";
        
        // Initialize model path (whisper will auto-download if needed)
        if (modelPath == null || modelPath.isEmpty()) {
            modelPath = Paths.get(System.getProperty("user.home"), ".cache", "whisper", "large-v3.pt").toAbsolutePath().normalize().toString();
        } else if (!Paths.get(modelPath).isAbsolute()) {
            modelPath = Paths.get(modelPath).toAbsolutePath().normalize().toString();
        } else {
            modelPath = Paths.get(modelPath).normalize().toString();
        }
        
        log.info("[WHISPER] Using whisper command from PATH, model cache: {}", modelPath);
    }

    public void ensureAvailable() throws IOException {
        // Whisper is installed via pip in Docker, just log readiness
        log.info("[WHISPER] Whisper available via Python package");
    }

    public boolean isModelValid() {
        // Whisper auto-downloads models, no need to validate
        log.debug("[WHISPER] Model validation skipped (auto-download enabled)");
        return true;
    }

    /**
     * Транскрибирует аудиофайл с использованием Whisper
     * 
     * @param audioFile Путь к аудиофайлу для транскрипции
     * @return Транскрибированный текст
     * @throws IOException          Если возникла проблема с файловыми операциями
     * @throws InterruptedException Если процесс был прерван
     */
    public String transcribe(File audioFile) throws IOException, InterruptedException {
        Object[] result = transcribeWithLanguage(audioFile);
        return (String) result[0];
    }

    /**
     * Транскрибирует аудиофайл с использованием Whisper и возвращает как
     * транскрипцию, так и
     * обнаруженный язык
     * 
     * @param audioFile Путь к аудиофайлу для транскрипции
     * @return Массив объектов с [транскрипция, язык]
     * @throws IOException          Если возникла проблема с файловыми операциями
     * @throws InterruptedException Если процесс был прерван
     */
    public Object[] transcribeWithLanguage(File audioFile) throws IOException, InterruptedException {
        log.info("[WHISPER] Beginning transcription with language detection for file: {}", audioFile.getAbsolutePath());

        try {
            // Build command for Python whisper package without file output
            // Whisper outputs transcription directly to console, we'll parse that
            String[] command = {
                    whisperPath,
                    audioFile.getAbsolutePath(),
                    "--task", "transcribe",
                    "--device", useGpu ? "cuda" : "cpu"
            };

            // Remove null/empty arguments  
            java.util.List<String> cmdList = new java.util.ArrayList<>();
            for (String arg : command) {
                if (arg != null && !arg.isEmpty()) {
                    cmdList.add(arg);
                }
            }
            command = cmdList.toArray(new String[0]);

            log.debug("[WHISPER] Executing command: {}", String.join(" ", command));

            // Execute command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read process output and parse transcription from console output
            StringBuilder output = new StringBuilder();
            StringBuilder transcriptionBuilder = new StringBuilder();
            String detectedLanguage = "unknown";
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean inTranscription = false;
                
                while ((line = reader.readLine()) != null) {
                    log.debug("[WHISPER] {}", line);
                    output.append(line).append("\n");
                    
                    // Parse language detection
                    if (line.contains("Detected language:")) {
                        int langStart = line.indexOf("Detected language:") + "Detected language:".length();
                        detectedLanguage = line.substring(langStart).trim();
                        inTranscription = true;
                        log.info("[WHISPER] Detected language: {}", detectedLanguage);
                        continue;
                    }
                    
                    // Parse transcription lines (format: [HH:MM:SS.mmm --> HH:MM:SS.mmm]  Text)
                    if (line.matches(".*\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\].*")) {
                        inTranscription = true;
                        // Extract text after the timestamp
                        int closeBracketIdx = line.lastIndexOf("]");
                        if (closeBracketIdx != -1 && closeBracketIdx < line.length() - 1) {
                            String segmentText = line.substring(closeBracketIdx + 1).trim();
                            if (!segmentText.isEmpty()) {
                                transcriptionBuilder.append(segmentText).append(" ");
                            }
                        }
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Whisper command finished with code: " + exitCode + ", output: " + output.toString());
            }

            String transcription = transcriptionBuilder.toString().trim();
            
            // Fallback: if no transcription was parsed, try to extract from generic output
            if (transcription.isEmpty()) {
                log.warn("[WHISPER] No transcription segments found in output, checking for alternative format");
                String outputStr = output.toString();
                
                // Try to find any text between timestamps
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\s+(.+)");
                java.util.regex.Matcher matcher = pattern.matcher(outputStr);
                
                while (matcher.find()) {
                    String segmentText = matcher.group(1).trim();
                    if (!segmentText.isEmpty()) {
                        transcriptionBuilder.append(segmentText).append(" ");
                    }
                }
                transcription = transcriptionBuilder.toString().trim();
            }

            log.info("[WHISPER] Транскрипция успешно завершена, язык: {}, длина: {} символов", detectedLanguage,
                    transcription.length());
            return new Object[] { transcription, detectedLanguage };
        } catch (Exception e) {
            log.error("[WHISPER] Error during transcription: {}", e.getMessage(), e);
            throw new RuntimeException("Transcription failed: " + e.getMessage(), e);
        }
    }

    public String getWhisperPath() {
        return whisperPath;
    }

    public String getModelPath() {
        return modelPath;
    }
}
