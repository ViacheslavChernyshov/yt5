package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.util.DownloadHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    @Value("${app.whisper.path:./whisper/whisper-cli.exe}")
    private String whisperPath;

    @Value("${app.whisper.model.path:./whisper/models/ggml-large-v3.bin}")
    private String modelPath;

    @Value("${app.whisper.use-gpu:false}")
    private boolean useGpu;

    @Value("${app.whisper.threads:4}")
    private int threads;

    private static final String WHISPER_URL = "https://github.com/ggerganov/whisper.cpp/releases/download/v1.8.2/whisper-cublas-12.4.0-bin-x64.zip";
    private static final String MODEL_DOWNLOAD_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin";

    public void ensureAvailable() throws IOException {
        Path exePath = Paths.get(whisperPath);
        Path modelFilePath = Paths.get(modelPath);

        // Download binary if missing
        if (Files.notExists(exePath)) {
            log.info("[WHISPER] Downloading Whisper...");
            DownloadHelper.downloadAndExtractZip(WHISPER_URL, exePath.getParent(), "Whisper");
            exePath.toFile().setExecutable(true);
        } else {
            log.info("[WHISPER] Found: {}", whisperPath);
        }

        // Download model if missing
        if (Files.notExists(modelFilePath)) {
            log.info("[WHISPER] Downloading ggml-large-v3 model...");
            DownloadHelper.downloadWithProgress(MODEL_DOWNLOAD_URL, modelFilePath, "Whisper model");
        } else {
            log.info("[WHISPER] Model found: {}", modelPath);
            // Verify model file integrity by checking its size
            File modelFile = new File(modelPath);
            if (modelFile.length() < 100000000) { // Less than 100MB likely means incomplete download
                log.warn("[WHISPER] Model file appears too small ({} bytes), may be corrupted", modelFile.length());
            }
        }
    }

    public boolean isModelValid() {
        Path modelFilePath = Paths.get(modelPath);
        if (Files.notExists(modelFilePath)) {
            log.warn("[WHISPER] Model file does not exist: {}", modelPath);
            return false;
        }

        File modelFile = modelFilePath.toFile();
        // Basic validation: model file for large-v3 should be around 1.8GB for
        // quantized version
        // Our file is about 115MB, so checking for minimum size to detect incomplete
        // downloads
        if (modelFile.length() < 100000000) { // Less than 100MB
            log.warn("[WHISPER] Model file size is suspiciously small ({} bytes), likely corrupted",
                    modelFile.length());
            return false;
        }

        return true;
    }

    /**
     * Transcribes audio file using Whisper
     * 
     * @param audioFile Path to the audio file to transcribe
     * @return Transcribed text
     * @throws IOException          If there's an issue with file operations
     * @throws InterruptedException If the process is interrupted
     */
    public String transcribe(File audioFile) throws IOException, InterruptedException {
        Object[] result = transcribeWithLanguage(audioFile);
        return (String) result[0];
    }

    /**
     * Transcribes audio file using Whisper and returns both transcription and
     * detected language
     * 
     * @param audioFile Path to the audio file to transcribe
     * @return Object array with [transcription, language]
     * @throws IOException          If there's an issue with file operations
     * @throws InterruptedException If the process is interrupted
     */
    public Object[] transcribeWithLanguage(File audioFile) throws IOException, InterruptedException {
        log.info("[WHISPER] Starting transcription with language detection for file: {}", audioFile.getAbsolutePath());

        // Check if model is valid before proceeding
        if (!isModelValid()) {
            log.warn("[WHISPER] Model validation failed, attempting to re-download model...");
            ensureAvailable(); // This will re-download the model if it doesn't exist or is invalid

            // Double-check after re-download
            if (!isModelValid()) {
                throw new RuntimeException("Whisper model is still invalid after re-download attempt");
            }
        }

        // Create a temporary base path for the output
        // We create a temp file to get a unique path, then delete it so Whisper can
        // create its own files with extensions
        Path tempBaseFile = Files.createTempFile("whisper_out_lang_", "");
        Files.deleteIfExists(tempBaseFile);
        String basePath = tempBaseFile.toAbsolutePath().toString();

        // Expected output files
        Path jsonOutputFile = Paths.get(basePath + ".json");
        Path txtOutputFile = Paths.get(basePath + ".txt");

        try {
            // Build the whisper command - using CPU with 4 threads for maximum quality
            String[] command = {
                    whisperPath,
                    "-m", modelPath,
                    "-f", audioFile.getAbsolutePath(),
                    "-of", basePath, // Output file base path
                    "--output-txt", // Output in plain text format
                    "-oj", // Output in JSON format
                    "--threads", String.valueOf(threads), // Use configured number of threads
                    useGpu ? "" : "-ng", // Conditionally disable GPU usage
                    "--language", "auto", // Auto-detect language
                    "--word-thold", "0.01" // Lower threshold for word detection
            };

            // Remove empty string arguments
            java.util.List<String> cmdList = new java.util.ArrayList<>();
            for (String arg : command) {
                if (arg != null && !arg.isEmpty()) {
                    cmdList.add(arg);
                }
            }
            command = cmdList.toArray(new String[0]);

            log.debug("[WHISPER] Executing command: {}", String.join(" ", command));

            // Execute the command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Read the output from the process
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[WHISPER] {}", line);
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "Whisper command failed with exit code: " + exitCode + ", output: " + output.toString());
            }

            String detectedLanguage = "unknown";
            String transcription = "";

            try {
                if (Files.exists(jsonOutputFile)) {
                    // Read the JSON output file which contains both language and transcription
                    String jsonOutput = Files.readString(jsonOutputFile);
                    log.debug("JSON output content: {}", jsonOutput);

                    // Extract language from JSON
                    if (jsonOutput.contains("\"language\"")) {
                        int langStart = jsonOutput.indexOf("\"language\":\"") + "\"language\":\"".length();
                        int langEnd = jsonOutput.indexOf("\"", langStart);
                        if (langStart > -1 && langEnd > langStart) {
                            detectedLanguage = jsonOutput.substring(langStart, langEnd);
                        }
                    }

                    // Extract transcription from JSON - look for the text segments
                    // More robust approach to extract all text from segments
                    if (jsonOutput.contains("\"segments\"")) {
                        // Find the segments array and extract all text values
                        StringBuilder transcriptBuilder = new StringBuilder();

                        // Find the segments array start and end
                        int segmentsStart = jsonOutput.indexOf("\"segments\"");
                        if (segmentsStart != -1) {
                            int arrayStart = jsonOutput.indexOf("[", segmentsStart);
                            if (arrayStart != -1) {
                                // Find the matching closing bracket for the array
                                int bracketCount = 0;
                                int pos = arrayStart;
                                for (; pos < jsonOutput.length(); pos++) {
                                    char c = jsonOutput.charAt(pos);
                                    if (c == '[')
                                        bracketCount++;
                                    else if (c == ']')
                                        bracketCount--;

                                    if (bracketCount == 0) {
                                        break;
                                    }
                                }

                                if (pos < jsonOutput.length()) {
                                    String segmentsArray = jsonOutput.substring(arrayStart, pos + 1);

                                    // Extract all text fields from the segments
                                    int textIndex = 0;
                                    while ((textIndex = segmentsArray.indexOf("\"text\":\"", textIndex)) != -1) {
                                        textIndex += "\"text\":\"".length();
                                        int textEnd = segmentsArray.indexOf("\"", textIndex);

                                        if (textEnd != -1) {
                                            String segmentText = segmentsArray.substring(textIndex, textEnd);
                                            // Properly unescape JSON strings
                                            segmentText = segmentText.replace("\\\"", "\"")
                                                    .replace("\\n", "\n")
                                                    .replace("\\t", "\t")
                                                    .replace("\\r", "\r");
                                            transcriptBuilder.append(segmentText.trim()).append(" ");
                                            textIndex = textEnd; // Continue searching after this position
                                        }
                                    }
                                }
                            }
                        }

                        transcription = transcriptBuilder.toString().trim();
                    }
                } else {
                    log.warn("[WHISPER] JSON output file not found: {}", jsonOutputFile);
                }
            } catch (Exception e) {
                log.warn("Could not parse JSON output, falling back to text file: {}", e.getMessage());
            }

            // Fallback to reading the text file
            if (transcription.isEmpty()) {
                try {
                    if (Files.exists(txtOutputFile)) {
                        transcription = Files.readString(txtOutputFile);
                        log.debug("Fallback transcription content: {}", transcription);

                        // Extract language from the text file if not already extracted
                        if (detectedLanguage.equals("unknown") && transcription.contains("auto-detected language:")) {
                            int langStart = transcription.indexOf("auto-detected language:")
                                    + "auto-detected language:".length();
                            int spaceEnd = transcription.indexOf(" ", langStart);
                            int newlineEnd = transcription.indexOf("\n", langStart);
                            int endPos = Math.min(
                                    spaceEnd != -1 ? spaceEnd : Integer.MAX_VALUE,
                                    newlineEnd != -1 ? newlineEnd : Integer.MAX_VALUE);
                            if (endPos != Integer.MAX_VALUE) {
                                detectedLanguage = transcription.substring(langStart, endPos).trim();
                                if (detectedLanguage.endsWith(",")) {
                                    detectedLanguage = detectedLanguage.substring(0, detectedLanguage.length() - 1)
                                            .trim();
                                }
                            }
                        }
                    } else {
                        log.warn("[WHISPER] Text output file not found: {}", txtOutputFile);
                    }
                } catch (IOException ioEx) {
                    log.error("Could not read text output file: {}", ioEx.getMessage());
                }
            }

            log.info("[WHISPER] Transcription completed successfully, language: {}, length: {} chars", detectedLanguage,
                    transcription.length());
            return new Object[] { transcription.trim(), detectedLanguage };
        } finally {
            // Clean up temporary files
            try {
                Files.deleteIfExists(jsonOutputFile);
            } catch (IOException e) {
                log.warn("Could not delete temporary JSON transcription file: {}", jsonOutputFile, e);
            }
            try {
                Files.deleteIfExists(txtOutputFile);
            } catch (IOException e) {
                log.warn("Could not delete temporary transcription file: {}", txtOutputFile, e);
            }
        }
    }

    public String getWhisperPath() {
        return whisperPath;
    }

    public String getModelPath() {
        return modelPath;
    }
}
