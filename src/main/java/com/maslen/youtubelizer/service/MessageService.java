package com.maslen.youtubelizer.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.ResourceBundle;

@Service
@Slf4j
public class MessageService {

    private static final String BUNDLE_NAME = "messages";

    public String getMessage(String key, String langCode) {
        Locale locale = getLocale(langCode);
        return getMessage(key, locale);
    }

    public String getMessage(String key, Locale locale) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
            return bundle.getString(key);
        } catch (Exception e) {
            log.error("Could not find message for key: {} and locale: {}", key, locale);
            return key;
        }
    }

    private Locale getLocale(String langCode) {
        if (langCode == null) {
            return Locale.ENGLISH;
        }

        return switch (langCode.toLowerCase()) {
            case "ru" -> Locale.of("ru");
            case "uk", "ua" -> Locale.of("uk");
            default -> Locale.ENGLISH;
        };
    }
}
