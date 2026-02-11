package com.maslen.youtubelizer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;

/**
 * Централизованный сервис для отправки сообщений и файлов в Telegram.
 * Устраняет дублирование sendMessage/sendDocument между YouTubeLizerBot и
 * TaskSchedulerService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final TelegramClient telegramClient;

    /**
     * Отправляет текстовое сообщение в чат.
     */
    public void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
            log.debug("[TELEGRAM] Сообщение отправлено в чат {}", chatId);
        } catch (TelegramApiException e) {
            log.error("[TELEGRAM] Не удалось отправить сообщение в чат {}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Отправляет файл (документ) в чат с подписью.
     */
    public void sendDocument(long chatId, File file, String caption) {
        SendDocument sendDocument = SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(file))
                .caption(caption)
                .build();
        try {
            telegramClient.execute(sendDocument);
            log.debug("[TELEGRAM] Документ отправлен в чат {}", chatId);
        } catch (TelegramApiException e) {
            log.error("[TELEGRAM] Не удалось отправить документ в чат {}: {}", chatId, e.getMessage(), e);
        }
    }

    /**
     * Возвращает TelegramClient для нестандартных операций (inline-клавиатуры,
     * invoice и т.д.)
     */
    public TelegramClient getClient() {
        return telegramClient;
    }
}
