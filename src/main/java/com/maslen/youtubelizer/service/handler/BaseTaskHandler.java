package com.maslen.youtubelizer.service.handler;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.service.MessageService;
import com.maslen.youtubelizer.service.TelegramNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseTaskHandler implements TaskHandler {

    protected final DownloadTaskRepository downloadTaskRepository;
    protected final TelegramNotificationService notificationService;
    protected final MessageService messageService;

    protected void updateTaskStatus(DownloadTask task, TaskStatus status) {
        task.setStatus(status);
        downloadTaskRepository.save(task);
    }

    protected void failTask(DownloadTask task, String errorMessage) {
        log.error("Task {} failed: {}", task.getId(), errorMessage);
        task.setStatus(TaskStatus.FAILED);
        task.setErrorMessage(truncateErrorMessage(errorMessage));
        downloadTaskRepository.save(task);

        notificationService.sendMessage(task.getChatId(),
                messageService.getMessage("common.error", task.getLanguageCode()) + " "
                        + truncateErrorMessage(errorMessage));
    }

    protected String truncateErrorMessage(String message) {
        if (message == null) {
            return "Unknown error";
        }
        String truncated = message.length() > 500 ? message.substring(0, 500) + "..." : message;
        return truncated.replaceAll("\\n+", " ").replaceAll("\\s+", " ");
    }

    protected void sendDonationMenu(long chatId, String languageCode) {
        try {
            List<InlineKeyboardRow> keyboard = new ArrayList<>();

            // Row 1: 10, 50, 100 stars
            InlineKeyboardRow row1 = new InlineKeyboardRow();
            row1.add(InlineKeyboardButton.builder().text("⭐ 10").callbackData("donate:10").build());
            row1.add(InlineKeyboardButton.builder().text("⭐ 50").callbackData("donate:50").build());
            row1.add(InlineKeyboardButton.builder().text("⭐ 100").callbackData("donate:100").build());
            keyboard.add(row1);

            // Row 2: Custom amount
            InlineKeyboardRow row2 = new InlineKeyboardRow();
            row2.add(InlineKeyboardButton.builder()
                    .text(messageService.getMessage("donation.custom", languageCode))
                    .callbackData("donate:custom")
                    .build());
            keyboard.add(row2);

            InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder().keyboard(keyboard).build();

            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text(messageService.getMessage("donation.choose_amount", languageCode))
                    .replyMarkup(markup)
                    .build();

            notificationService.getClient().execute(message);
        } catch (TelegramApiException e) {
            log.warn("Failed to send donation menu: {}", e.getMessage());
        }
    }
}
