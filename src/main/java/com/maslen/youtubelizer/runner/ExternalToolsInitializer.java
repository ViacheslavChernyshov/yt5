package com.maslen.youtubelizer.runner;

import com.maslen.youtubelizer.service.FfmpegService;
import com.maslen.youtubelizer.service.LlamaService;
import com.maslen.youtubelizer.service.WhisperService;
import com.maslen.youtubelizer.service.YtDlpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalToolsInitializer implements ApplicationRunner {

    private final YtDlpService ytDlpService;
    private final FfmpegService ffmpegService;
    private final WhisperService whisperService;
    private final LlamaService llamaService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Initializing external tools...");
        try {
            log.info("Ensuring yt-dlp is available...");
            ytDlpService.ensureAvailable();

            log.info("Ensuring FFmpeg is available...");
            ffmpegService.ensureAvailable();

            log.info("Ensuring Whisper is available...");
            whisperService.ensureAvailable();

            log.info("Ensuring Llama.cpp and Qwen model are available...");
            llamaService.ensureAvailable();

            log.info("All external tools initialized successfully");
        } catch (IOException e) {
            log.error("Failed to initialize external tools", e);
            throw e;
        }
    }
}
