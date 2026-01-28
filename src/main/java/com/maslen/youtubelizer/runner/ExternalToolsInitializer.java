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
        log.info("Инициализация внешних инструментов...");
        try {
            log.info("Проверка доступности yt-dlp...");
            ytDlpService.ensureAvailable();

            log.info("Проверка доступности FFmpeg...");
            ffmpegService.ensureAvailable();

            log.info("Проверка доступности Whisper...");
            whisperService.ensureAvailable();

            log.info("Проверка доступности Llama.cpp и модели Qwen...");
            llamaService.ensureAvailable();

            log.info("Все внешние инструменты успешно инициализированы");
        } catch (IOException e) {
            log.error("Не удалось инициализировать внешние инструменты", e);
            throw e;
        }
    }
}
