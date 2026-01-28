package com.maslen.youtubelizer.bot;

import com.maslen.youtubelizer.entity.Channel;
import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.entity.Request;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.model.TaskType;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.service.YouTubeService;
import com.maslen.youtubelizer.service.YtDlpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class YouTubeLizerBot implements LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final String botToken;
    private TelegramBotsLongPollingApplication botsApplication;

    @Autowired
    private YouTubeService youTubeService;

    @Autowired
    private YtDlpService ytDlpService;

    @Autowired
    private DownloadTaskRepository downloadTaskRepository;

    public YouTubeLizerBot(@Value("${telegram.bot.token}") String botToken, TelegramClient telegramClient) {
        this.botToken = botToken;
        this.telegramClient = telegramClient;
    }

    @PostConstruct
    public void start() {
        try {
            botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(botToken, this);
            log.info("[BOT] YouTubeLizer Bot успешно запущен!");
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось запустить бота: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public void stop() {
        if (botsApplication != null) {
            try {
                botsApplication.close();
                log.info("[BOT] Бот остановлен");
            } catch (Exception e) {
                log.error("[BOT] Не удалось остановить бота: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String userName = update.getMessage().getFrom().getFirstName();
            Long userId = update.getMessage().getFrom().getId();

            log.info("[BOT] Получено сообщение от {}: {}", userName, messageText);

            if (messageText.equals("/start")) {
                sendMessage(chatId, "👋 Привет, " + userName + "!\n\n" +
                        "Я YouTubeLizer Bot. Отправь мне ссылку на YouTube видео или шортс, " +
                        "и я помогу тебе с его обработкой!\n\n" +
                        "📝 Просто отправь ссылку на видео.");
            } else if (youTubeService.isValidYouTubeLink(messageText)) {
                // Логируем запрос
                String videoId = youTubeService.extractVideoId(messageText);
                Request request = youTubeService.createRequest(userId, userName, messageText, true, messageText,
                        videoId, null);

                try {
                    // Извлекаем информацию о канале
                    Channel channel = youTubeService.processYouTubeUrl(messageText);

                    // Обновляем запрос информацией о канале
                    request.setChannel(channel);
                    // Сохраняем обновленный запрос
                    youTubeService.createRequest(userId, userName, messageText, true, messageText, videoId, channel);

                    sendMessageWithKeyboard(chatId, "🎬 Валидная YouTube ссылка найдена!\n" +
                            "Видео ID: " + videoId + "\n" +
                            "Канал: "
                            + (channel.getChannelTitle() != null ? channel.getChannelTitle() : "Неизвестный канал")
                            + "\n\n" +
                            "Выберите действие:", videoId);

                } catch (Exception e) {
                    log.error("[BOT] Ошибка обработки ссылки YouTube: {}", e.getMessage(), e);

                    // Логируем невалидный запрос
                    youTubeService.createRequest(userId, userName, messageText, false, messageText, videoId, null);

                    sendMessage(chatId, "❌ Произошла ошибка при обработке YouTube ссылки.\n" +
                            "Пожалуйста, проверьте ссылку и попробуйте снова.");
                }
            } else {
                // Log invalid request
                youTubeService.createRequest(userId, userName, messageText, false, messageText, null, null);

                sendMessage(chatId, "❌ Отправленная вами ссылка не является валидной YouTube ссылкой.\n" +
                        "Пожалуйста, отправьте ссылку на YouTube видео или шортс.\n\n" +
                        "Примеры:\n" +
                        "- https://www.youtube.com/watch?v=...\n" +
                        "- https://youtu.be/...\n" +
                        "- https://www.youtube.com/shorts/...");
            }
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();
        try {
            telegramClient.execute(message);
            log.debug("[BOT] Сообщение отправлено в чат {}", chatId);
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось отправить сообщение: {}", e.getMessage(), e);
        }
    }

    private void sendMessageWithKeyboard(long chatId, String text, String videoId) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(createProcessingOptionsKeyboard(videoId))
                .build();
        try {
            telegramClient.execute(message);
            log.debug("[BOT] Сообщение с клавиатурой отправлено в чат {}", chatId);
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось отправить сообщение с клавиатурой: {}", e.getMessage(), e);
            // Запасной вариант: отправка обычного сообщения
            sendMessage(chatId, text);
        }
    }

    private InlineKeyboardMarkup createProcessingOptionsKeyboard(String videoId) {
        // Создаем клавиатуру, используя правильную структуру для этой версии библиотеки
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow> keyboard = new ArrayList<>();

        // Первый ряд: Скачать видео и Скачать аудио
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row1 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row1.add(InlineKeyboardButton.builder()
                .text("📹 Загрузка видео")
                .callbackData("download_video:" + videoId)
                .build());
        row1.add(InlineKeyboardButton.builder()
                .text("🎧 Загрузка аудио")
                .callbackData("download_audio:" + videoId)
                .build());
        keyboard.add(row1);

        // Второй ряд: Распознавание речи и Нормализация текста
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row2 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row2.add(InlineKeyboardButton.builder()
                .text("🗣️ Распознавание речи")
                .callbackData("speech_recognition:" + videoId)
                .build());
        row2.add(InlineKeyboardButton.builder()
                .text("📝 Нормализация текста")
                .callbackData("normalize_text:" + videoId)
                .build());
        keyboard.add(row2);

        // Третий ряд: Выполнить все и запаковать в ZIP
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row3 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row3.add(InlineKeyboardButton.builder()
                .text("📦 Выполнить все и запаковать ZIP")
                .callbackData("process_all_zip:" + videoId)
                .build());
        keyboard.add(row3);

        return InlineKeyboardMarkup.builder()
                .keyboard(keyboard)
                .build();
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String callbackData = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        String userId = callbackQuery.getFrom().getId().toString();

        log.info("[BOT] Получен callback: {} от пользователя: {}", callbackData, userId);

        // Обработка callback на основе данных
        String[] parts = callbackData.split(":");
        String action = parts[0];
        String videoId = parts.length > 1 ? parts[1] : null;

        String actionName = "";
        String responseText = "";
        switch (action) {
            case "download_video":
                actionName = "📹 Загрузка видео";
                responseText = "📥 Задача добавлена в очередь. Ожидайте загрузки видео...";
                queueDownloadTask(chatId, videoId, TaskType.VIDEO);
                break;
            case "download_audio":
                actionName = "🎧 Загрузка аудио";
                responseText = "📥 Задача добавлена в очередь. Ожидайте загрузки аудио...";
                queueDownloadTask(chatId, videoId, TaskType.AUDIO);
                break;
            case "speech_recognition":
                actionName = "🗣️ Распознавание речи";
                responseText = "🎙️ Задача добавлена в очередь. Начинается распознавание речи...";
                queueDownloadTask(chatId, videoId, TaskType.SPEECH_RECOGNITION);
                break;
            case "normalize_text":
                actionName = "📝 Нормализация текста";
                responseText = "📝 Задача добавлена в очередь. Начинается нормализация текста...";
                queueDownloadTask(chatId, videoId, TaskType.TEXT_NORMALIZATION);
                break;
            case "process_all_zip":
                actionName = "📦 Полная обработка (ZIP)";
                responseText = "📦 Задача добавлена в очередь. Готовлю ZIP-архив со всеми материалами...";
                queueDownloadTask(chatId, videoId, TaskType.FULL_PROCESSING_ZIP);
                break;
            default:
                actionName = "Неизвестное действие";
                responseText = "Неизвестная команда";
        }

        // Отправка ответа пользователю
        sendMessage(chatId, responseText);

        // Ответ на callback query, чтобы убрать индикатор загрузки
        try {
            telegramClient.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .text(responseText)
                    .showAlert(false)
                    .build());
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось ответить на callback query: {}", e.getMessage(), e);
        }

        // Редактирование исходного сообщения для отображения выбора и удаления
        // клавиатуры
        try {
            EditMessageText editMessage = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text("✅ Выбрано: " + actionName)
                    .replyMarkup(null) // Удаляем клавиатуру
                    .build();
            telegramClient.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось отредактировать сообщение: {}", e.getMessage(), e);
        }
    }

    private void queueDownloadTask(long chatId, String videoId, TaskType type) {
        DownloadTask task = new DownloadTask();
        task.setChatId(chatId);
        task.setVideoId(videoId);
        task.setType(type);
        task.setStatus(TaskStatus.PENDING);
        downloadTaskRepository.save(task);
        log.info("Задача на скачивание добавлена в очередь: videoId={}, type={}", videoId, type);
    }
}
