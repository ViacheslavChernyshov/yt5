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
import com.maslen.youtubelizer.service.MessageService;
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
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
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

    @Autowired
    private MessageService messageService;

    @Autowired
    private com.maslen.youtubelizer.service.TaskSchedulerService taskSchedulerService;

    // Track users waiting to enter custom donation amount: chatId -> languageCode
    private final java.util.Map<Long, String> pendingDonationUsers = new java.util.concurrent.ConcurrentHashMap<>();

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
            String languageCode = update.getMessage().getFrom().getLanguageCode();

            log.info("[BOT] Получено сообщение от {} (lang: {}): {}", userName, languageCode, messageText);

            // Check if user is entering a custom donation amount
            if (handleCustomDonationAmount(chatId, messageText, languageCode)) {
                return; // Message was handled as donation amount
            }

            if (messageText.equals("/start") || messageText.equals("/help")) {
                sendMessage(chatId, messageService.getMessage("bot.welcome", languageCode));
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

                    sendMessageWithKeyboard(
                            chatId, messageService.getMessage("bot.select_action", languageCode) + "\n" +
                                    "Video ID: " + videoId + "\n" +
                                    "Channel: "
                                    + (channel.getChannelTitle() != null ? channel.getChannelTitle()
                                            : "Unknown channel"),
                            videoId, languageCode);

                } catch (Exception e) {
                    log.error("[BOT] Ошибка обработки ссылки YouTube: {}", e.getMessage(), e);

                    // Логируем невалидный запрос
                    youTubeService.createRequest(userId, userName, messageText, false, messageText, videoId, null);

                    sendMessage(chatId, messageService.getMessage("common.error", languageCode) + e.getMessage());
                }
            } else {
                // Log invalid request
                youTubeService.createRequest(userId, userName, messageText, false, messageText, null, null);

                sendMessage(chatId, messageService.getMessage("bot.invalid_link", languageCode));
            }
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        } else if (update.hasPreCheckoutQuery()) {
            // Approve pre-checkout query for Telegram Stars payments
            handlePreCheckoutQuery(update.getPreCheckoutQuery());
        } else if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
            // Handle successful payment
            handleSuccessfulPayment(update.getMessage());
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

    private void sendMessageWithKeyboard(long chatId, String text, String videoId, String languageCode) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(createProcessingOptionsKeyboard(videoId, languageCode))
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

    private InlineKeyboardMarkup createProcessingOptionsKeyboard(String videoId, String languageCode) {
        // Создаем клавиатуру, используя правильную структуру для этой версии библиотеки
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow> keyboard = new ArrayList<>();

        // Первый ряд: Скачать видео и Скачать аудио
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row1 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row1.add(InlineKeyboardButton.builder()
                .text(messageService.getMessage("bot.button.video", languageCode))
                .callbackData("download_video:" + videoId)
                .build());
        row1.add(InlineKeyboardButton.builder()
                .text(messageService.getMessage("bot.button.audio", languageCode))
                .callbackData("download_audio:" + videoId)
                .build());
        keyboard.add(row1);

        // Второй ряд: Распознавание речи и Нормализация текста
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row2 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row2.add(InlineKeyboardButton.builder()
                .text(messageService.getMessage("bot.button.text", languageCode))
                .callbackData("speech_recognition:" + videoId)
                .build());
        row2.add(InlineKeyboardButton.builder()
                .text(messageService.getMessage("common.normalizing", languageCode))
                .callbackData("normalize_text:" + videoId)
                .build());
        keyboard.add(row2);

        // Третий ряд: Выполнить все и запаковать в ZIP
        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow row3 = new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow();
        row3.add(InlineKeyboardButton.builder()
                .text(messageService.getMessage("bot.button.zip", languageCode))
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
        String languageCode = callbackQuery.getFrom().getLanguageCode();

        log.info("[BOT] Получен callback: {} от пользователя: {}", callbackData, userId);

        // Обработка callback на основе данных
        String[] parts = callbackData.split(":");
        String action = parts[0];
        String videoId = parts.length > 1 ? parts[1] : null;

        String actionName = "";
        String responseText = "";
        switch (action) {
            case "download_video":
                actionName = messageService.getMessage("bot.button.video", languageCode);
                responseText = messageService.getMessage("bot.task_scheduled", languageCode);
                queueDownloadTask(chatId, videoId, TaskType.VIDEO, languageCode);
                break;
            case "download_audio":
                actionName = messageService.getMessage("bot.button.audio", languageCode);
                responseText = messageService.getMessage("bot.task_scheduled", languageCode);
                queueDownloadTask(chatId, videoId, TaskType.AUDIO, languageCode);
                break;
            case "speech_recognition":
                actionName = messageService.getMessage("bot.button.text", languageCode);
                responseText = messageService.getMessage("bot.task_scheduled", languageCode);
                queueDownloadTask(chatId, videoId, TaskType.SPEECH_RECOGNITION, languageCode);
                break;
            case "normalize_text":
                actionName = messageService.getMessage("common.normalizing", languageCode);
                responseText = messageService.getMessage("bot.task_scheduled", languageCode);
                queueDownloadTask(chatId, videoId, TaskType.TEXT_NORMALIZATION, languageCode);
                break;
            case "process_all_zip":
                actionName = messageService.getMessage("bot.button.zip", languageCode);
                responseText = messageService.getMessage("bot.task_scheduled", languageCode);
                queueDownloadTask(chatId, videoId, TaskType.FULL_PROCESSING_ZIP, languageCode);
                break;
            case "donate":
                handleDonateCallback(chatId, messageId, videoId, languageCode, callbackQuery.getId());
                return; // Early return, handled separately
            default:
                actionName = "Unknown action";
                responseText = "Unknown command";
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
                    .text("✅ " + actionName)
                    .replyMarkup(null) // Удаляем клавиатуру
                    .build();
            telegramClient.execute(editMessage);
        } catch (TelegramApiException e) {
            log.error("[BOT] Не удалось отредактировать сообщение: {}", e.getMessage(), e);
        }
    }

    private void queueDownloadTask(long chatId, String videoId, TaskType type, String languageCode) {
        DownloadTask task = new DownloadTask();
        task.setChatId(chatId);
        task.setVideoId(videoId);
        task.setType(type);
        task.setStatus(TaskStatus.PENDING);
        task.setLanguageCode(languageCode);
        downloadTaskRepository.save(task);
        log.info("Task queued: videoId={}, type={}, lang={}", videoId, type, languageCode);
    }

    /**
     * Handles pre-checkout query for Telegram Stars payments.
     * Must respond within 10 seconds.
     */
    private void handlePreCheckoutQuery(PreCheckoutQuery preCheckoutQuery) {
        log.info("[BOT] Pre-checkout query received: payload={}", preCheckoutQuery.getInvoicePayload());

        try {
            // Always approve the pre-checkout for donations
            AnswerPreCheckoutQuery answer = AnswerPreCheckoutQuery.builder()
                    .preCheckoutQueryId(preCheckoutQuery.getId())
                    .ok(true)
                    .build();
            telegramClient.execute(answer);
            log.info("[BOT] Pre-checkout approved for user {}", preCheckoutQuery.getFrom().getId());
        } catch (TelegramApiException e) {
            log.error("[BOT] Failed to answer pre-checkout query: {}", e.getMessage(), e);
        }
    }

    /**
     * Handles successful payment for Telegram Stars donations.
     */
    private void handleSuccessfulPayment(Message message) {
        var payment = message.getSuccessfulPayment();
        long chatId = message.getChatId();
        String languageCode = message.getFrom().getLanguageCode();

        log.info("[BOT] Successful payment: amount={} {}, from user {}",
                payment.getTotalAmount(), payment.getCurrency(), message.getFrom().getId());

        // Thank the user for their donation
        sendMessage(chatId, messageService.getMessage("donation.thank_you", languageCode));
    }

    /**
     * Handles donation callback (10, 50, 100 stars or custom amount).
     */
    private void handleDonateCallback(long chatId, int messageId, String amountStr, String languageCode,
            String callbackId) {
        try {
            // Answer the callback query first
            telegramClient.execute(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .build());

            // Remove the donation menu
            telegramClient.execute(org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());

            if ("custom".equals(amountStr)) {
                // Ask user to enter custom amount
                pendingDonationUsers.put(chatId, languageCode);
                sendMessage(chatId, messageService.getMessage("donation.enter_amount", languageCode));
            } else {
                // Send invoice with selected amount
                int amount = Integer.parseInt(amountStr);
                taskSchedulerService.sendDonationInvoice(chatId, amount, languageCode);
            }
        } catch (TelegramApiException e) {
            log.error("[BOT] Failed to handle donate callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Handles custom donation amount entered by user.
     * Returns true if message was handled as donation amount.
     */
    private boolean handleCustomDonationAmount(long chatId, String text, String languageCode) {
        if (!pendingDonationUsers.containsKey(chatId)) {
            return false;
        }

        String savedLanguageCode = pendingDonationUsers.remove(chatId);

        try {
            int amount = Integer.parseInt(text.trim());
            if (amount < 1 || amount > 2500) {
                sendMessage(chatId, "⚠️ Please enter a number between 1 and 2500");
                pendingDonationUsers.put(chatId, savedLanguageCode); // Put back for retry
                return true;
            }

            taskSchedulerService.sendDonationInvoice(chatId, amount, savedLanguageCode);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "⚠️ Please enter a valid number");
            pendingDonationUsers.put(chatId, savedLanguageCode); // Put back for retry
        }

        return true;
    }
}
