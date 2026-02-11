package com.maslen.youtubelizer.service;

import com.maslen.youtubelizer.entity.DownloadTask;
import com.maslen.youtubelizer.model.TaskStatus;
import com.maslen.youtubelizer.repository.DownloadTaskRepository;
import com.maslen.youtubelizer.service.handler.TaskHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private final DownloadTaskRepository downloadTaskRepository;
    private final TelegramNotificationService notificationService;
    private final MessageService messageService;
    private final List<TaskHandler> taskHandlers;

    @Scheduled(fixedDelay = 10000)
    public void processNextTask() {
        try {
            resetStuckTasks();

            Optional<DownloadTask> taskOpt = downloadTaskRepository
                    .findTopByStatusOrderByCreatedAtAsc(TaskStatus.PENDING);

            if (taskOpt.isPresent()) {
                DownloadTask task = taskOpt.get();
                processTask(task);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("BeanCreation") || msg.contains("DataSourceProperties"))) {
                log.warn("Application context is shutting down or not ready: {}", msg);
            } else {
                log.warn("Error processing tasks: {}", msg);
            }
        }
    }

    private void resetStuckTasks() {
        try {
            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            List<DownloadTask> stuckTasks = downloadTaskRepository.findByStatusAndUpdatedAtBefore(TaskStatus.PROCESSING,
                    oneHourAgo);

            if (!stuckTasks.isEmpty()) {
                log.info("Found {} stuck tasks, resetting to PENDING", stuckTasks.size());

                for (DownloadTask task : stuckTasks) {
                    log.warn("Resetting stuck task id: {}, videoId: {}, last updated: {}",
                            task.getId(), task.getVideoId(), task.getUpdatedAt());

                    task.setStatus(TaskStatus.PENDING);
                    downloadTaskRepository.save(task);
                }
            }
        } catch (Exception e) {
            log.warn("Error resetting stuck tasks: {}", e.getMessage());
        }
    }

    private void processTask(DownloadTask task) {
        log.info("Processing task id: {} type: {}", task.getId(), task.getType());

        task.setStatus(TaskStatus.PROCESSING);
        downloadTaskRepository.save(task);

        try {
            TaskHandler handler = taskHandlers.stream()
                    .filter(h -> h.canHandle(task.getType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown task type: " + task.getType()));

            handler.handle(task);

            // Send donation menu only for successfully completed tasks
            if (task.getStatus() == TaskStatus.COMPLETED) {
                sendDonationMenu(task.getChatId(), task.getLanguageCode());
            }

        } catch (Exception e) {
            log.error("Error processing task {}", task.getId(), e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(truncateErrorMessage(e.getMessage()));
            downloadTaskRepository.save(task);

            notificationService.sendMessage(task.getChatId(),
                    messageService.getMessage("common.error", task.getLanguageCode()) + " "
                            + truncateErrorMessage(e.getMessage()));
        }
    }

    private String truncateErrorMessage(String message) {
        if (message == null) {
            return "Unknown error";
        }
        String truncated = message.length() > 500 ? message.substring(0, 500) + "..." : message;
        return truncated.replaceAll("\\n+", " ").replaceAll("\\s+", " ");
    }

    private void sendDonationMenu(long chatId, String languageCode) {
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
            log.info("Donation menu sent to chat {}", chatId);
        } catch (TelegramApiException e) {
            log.warn("Failed to send donation menu: {}", e.getMessage());
        }
    }

    public void sendDonationInvoice(long chatId, int amount, String languageCode) {
        try {
            String label = "⭐ " + amount + " Stars";
            SendInvoice sendInvoice = SendInvoice.builder()
                    .chatId(chatId)
                    .title(messageService.getMessage("donation.title", languageCode))
                    .description(messageService.getMessage("donation.description", languageCode))
                    .payload("donation_" + amount + "_stars_" + System.currentTimeMillis())
                    .currency("XTR")
                    .providerToken("")
                    .price(new LabeledPrice(label, amount))
                    .startParameter("donate")
                    .build();

            notificationService.getClient().execute(sendInvoice);
            log.info("Donation invoice for {} stars sent to chat {}", amount, chatId);
        } catch (TelegramApiException e) {
            log.warn("Failed to send donation invoice: {}", e.getMessage());
        }
    }
}
