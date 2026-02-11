package com.maslen.youtubelizer.model;

/**
 * Результат транскрипции аудиофайла.
 *
 * @param text     Транскрибированный текст
 * @param language Обнаруженный язык аудио
 */
public record TranscriptionResult(String text, String language) {
}
