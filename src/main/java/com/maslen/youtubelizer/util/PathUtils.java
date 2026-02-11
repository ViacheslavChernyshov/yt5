package com.maslen.youtubelizer.util;

import java.nio.file.Paths;

/**
 * Утилита для нормализации путей к внешним инструментам.
 * Исключает дублирование логики инициализации путей в сервисах.
 */
public final class PathUtils {

    private PathUtils() {
        // Utility class
    }

    /**
     * Разрешает путь к внешнему инструменту:
     * <ul>
     * <li>Если {@code configured} пуст или null → возвращает
     * {@code defaultValue}</li>
     * <li>Если {@code configured} — относительный путь → преобразует в
     * абсолютный</li>
     * <li>Если {@code configured} — абсолютный путь → нормализует</li>
     * </ul>
     *
     * @param configured   настроенный путь из конфигурации (может быть null или
     *                     пустым)
     * @param defaultValue значение по умолчанию (команда в PATH, например "ffmpeg")
     * @return нормализованный путь
     */
    public static String resolvePath(String configured, String defaultValue) {
        if (configured == null || configured.isEmpty()) {
            return defaultValue;
        }
        if (Paths.get(configured).isAbsolute()) {
            return Paths.get(configured).normalize().toString();
        }
        return Paths.get(configured).toAbsolutePath().normalize().toString();
    }
}
