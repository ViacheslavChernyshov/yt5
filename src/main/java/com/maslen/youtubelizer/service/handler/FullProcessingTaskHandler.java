package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Video;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.repository.VideoRepository;
import com.maslen.youtubelizer.service.MessageService;
import com.maslen.youtubelizer.service.NormalizationService;
import com.maslen.youtubelizer.service.TelegramNotificationService;
import com.maslen.youtubelizer.service.TranscriptionService;
import com.maslen.youtubelizer.service.YtDlpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Component
public class FullProcessingTaskHandler extends BaseTaskHandler {

    private final YtDlpService ytDlpService;
    private final TranscriptionService transcriptionService;
    private final NormalizationService normalizationService;
    private final VideoRepository videoRepository;

    public FullProcessingTaskHandler(DownloadTaskRepository downloadTaskRepository,
            TelegramNotificationService notificationService,
            MessageService messageService,
            YtDlpService ytDlpService,
            TranscriptionService transcriptionService,
            NormalizationService normalizationService,
            VideoRepository videoRepository) {
        super(downloadTaskRepository, notificationService, messageService);
        this.ytDlpService = ytDlpService;
        this.transcriptionService = transcriptionService;
        this.normalizationService = normalizationService;
        this.videoRepository = videoRepository;
    }

    @Override
    public boolean canHandle(TaskType type) {
        return type == TaskType.FULL_PROCESSING_ZIP;
    }

    @Override
    public void handle(DownloadTask task) {
        log.info("[ZIP] Starting full processing for video: {}", task.getVideoId());
        Path tempDir = null;
        try {
            String url = "https://www.youtube.com/watch?v=" + task.getVideoId();
            tempDir = Files.createTempDirectory("zip_" + task.getVideoId());

            // Step 1: Download Media (ALWAYS)
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.downloading", task.getLanguageCode()));
            File videoFile = ytDlpService.downloadVideo(url, tempDir, "video");
            File audioFile = ytDlpService.downloadAudio(url, tempDir, "audio");

            if (videoFile == null || !videoFile.exists() || audioFile == null || !audioFile.exists()) {
                throw new IOException("Failed to download media files");
            }

            // Step 2: Get Transcription
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.transcribing", task.getLanguageCode()));
            Optional<Video> videoOpt = videoRepository.findByVideoId(task.getVideoId());
            Video videoRecord;

            if (videoOpt.isPresent() && videoOpt.get().getTranscriptionText() != null
                    && !videoOpt.get().getTranscriptionText().isEmpty()) {
                log.info("[ZIP] Found existing transcription for {}", task.getVideoId());
                videoRecord = videoOpt.get();
            } else {
                log.info("[ZIP] Transcription not found, recognizing from downloaded audio...");
                // Use the ALREADY downloaded audio file for transcription
                videoRecord = transcriptionService.transcribeFile(task, audioFile);
                if (videoRecord == null) {
                    throw new IOException("Transcription failed");
                }
            }

            // Step 3: Get Normalization
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.normalizing", task.getLanguageCode()));
            String normalizedText;
            if (videoRecord.getNormalizedText() != null && !videoRecord.getNormalizedText().isEmpty()) {
                log.info("[ZIP] Found existing normalization for {}", task.getVideoId());
                normalizedText = videoRecord.getNormalizedText();
            } else {
                log.info("[ZIP] Normalization not found, generating...");
                String transcription = videoRecord.getTranscriptionText();
                String language = videoRecord.getOriginalLanguage();

                normalizedText = normalizationService.normalizeText(transcription, language);
                if (normalizedText == null) {
                    throw new IOException("Normalization returned empty result");
                }

                videoRecord.setNormalizedText(normalizedText);
                videoRepository.save(videoRecord);
            }

            // Step 4: Create Text Files
            File transcriptionFile = tempDir.resolve("transcription.txt").toFile();
            Files.writeString(transcriptionFile.toPath(), videoRecord.getTranscriptionText());

            File normalizedFile = tempDir.resolve("normalized.txt").toFile();
            Files.writeString(normalizedFile.toPath(), normalizedText);

            // Step 5: Pack ZIP
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.packing", task.getLanguageCode()));
            File zipFile = tempDir.resolve("content.zip").toFile();
            try (FileOutputStream fos = new FileOutputStream(zipFile);
                    ZipOutputStream zos = new ZipOutputStream(fos)) {

                addToZip(videoFile, zos);
                addToZip(audioFile, zos);
                addToZip(transcriptionFile, zos);
                addToZip(normalizedFile, zos);
            }

            // Step 6: Send ZIP
            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.sending", task.getLanguageCode()));
            notificationService.sendDocument(task.getChatId(), zipFile,
                    messageService.getMessage("task.completed.full_processing_caption", task.getLanguageCode()));

            updateTaskStatus(task, TaskStatus.COMPLETED);
            log.info("[ZIP] Completed full processing for video: {}", task.getVideoId());

        } catch (Exception e) {
            log.error("[ZIP] Error in full processing for video: {}", task.getVideoId(), e);
            failTask(task, "Error: " + e.getMessage());
        } finally {
            // Step 7: Cleanup
            if (tempDir != null) {
                try (Stream<Path> walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                    log.debug("[ZIP] Cleaned up temporary directory: {}", tempDir);
                } catch (IOException e) {
                    log.warn("[ZIP] Failed to clean up temp dir: {}", e.getMessage());
                }
            }
        }
    }

    private void addToZip(File file, ZipOutputStream zos) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zos.putNextEntry(zipEntry);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) >= 0) {
                zos.write(buffer, 0, length);
            }
            zos.closeEntry();
        }
    }
}
