package com.maslen.youtubelizer.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Slf4j
@Component
public class YouTubeLizerBot implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private TelegramBotsLongPollingApplication botsApplication;

    public YouTubeLizerBot(@Value("${telegram.bot.token}") String botToken) {
        this.botToken = botToken;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @PostConstruct
    public void start() {
        try {
            botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(botToken, this);
            log.info("[BOT] YouTubeLizer Bot started successfully!");
        } catch (TelegramApiException e) {
            log.error("[BOT] Failed to start bot: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        if (botsApplication != null) {
            try {
                botsApplication.close();
                log.info("[BOT] Bot stopped");
            } catch (Exception e) {
                log.error("[BOT] Failed to stop bot: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userName = update.getMessage().getFrom().getFirstName();

            log.info("[BOT] Received message from {}: {}", userName, messageText);

            if (messageText.equals("/start")) {
                sendMessage(chatId, "👋 Привет, " + userName + "!\n\n" +
                        "Я YouTubeLizer Bot. Отправь мне ссылку на YouTube видео, " +
                        "и я извлеку из него текст!\n\n" +
                        "📝 Просто отправь ссылку на видео.");
            } else if (messageText.contains("youtube.com") || messageText.contains("youtu.be")) {
                sendMessage(chatId, "🎬 Получил ссылку! Начинаю обработку видео...\n" +
                        "Это может занять несколько минут.");
                log.info("[BOT] Processing YouTube link: {}", messageText);
            } else {
                sendMessage(chatId, "❓ Отправь мне ссылку на YouTube видео для обработки.\n" +
                        "Например: https://www.youtube.com/watch?v=...");
            }
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
            log.debug("[BOT] Message sent to chat {}", chatId);
        } catch (TelegramApiException e) {
            log.error("[BOT] Failed to send message: {}", e.getMessage(), e);
        }
    }
}
