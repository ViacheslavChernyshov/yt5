package com.maslen.youtubelizer.util;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Утилита для скачивания файлов с прогрессом и распаковки архивов.
 * Используется WhisperService и LlamaService.
 */
@Slf4j
public class DownloadHelper {

    private DownloadHelper() {
        // Utility class
    }

    /**
     * Получает ожидаемый размер файла из HTTP заголовков
     */
    public static long getExpectedSize(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("HEAD");
            long size = connection.getContentLengthLong();
            connection.disconnect();
            return size;
        } catch (IOException e) {
            log.warn("[DOWNLOAD] Не удалось получить размер файла: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Скачивает файл с отображением прогресса
     */
    public static void downloadWithProgress(String url, Path target, String name) throws IOException {
        Files.createDirectories(target.getParent());
        Files.deleteIfExists(target);

        long expectedSize = getExpectedSize(url);
        if (expectedSize > 0) {
            log.info("[DOWNLOAD] {} (~{} МБ)", name, expectedSize / (1024 * 1024));
        } else {
            log.info("[DOWNLOAD] {}", name);
        }

        try (InputStream in = URI.create(url).toURL().openStream();
                OutputStream out = new FileOutputStream(target.toFile())) {

            byte[] buffer = new byte[8192];
            long totalBytesRead = 0;
            long lastLoggedMB = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;

                // Логируем прогресс каждые 100 МБ
                long currentMB = totalBytesRead / (1024 * 1024);
                if (currentMB - lastLoggedMB >= 100) {
                    if (expectedSize > 0) {
                        long expectedMB = expectedSize / (1024 * 1024);
                        log.info("[DOWNLOAD] {} | {} / {} МБ ({}%)",
                                name, currentMB, expectedMB, (currentMB * 100) / expectedMB);
                    } else {
                        log.info("[DOWNLOAD] {} | {} МБ", name, currentMB);
                    }
                    lastLoggedMB = currentMB;
                }
            }

            log.info("[DOWNLOAD] {} | Завершено ({} байт)", name, totalBytesRead);

            // Проверка размера
            if (expectedSize > 0) {
                long actualSize = Files.size(target);
                if (actualSize != expectedSize) {
                    log.warn("[DOWNLOAD] {} | Размер не совпадает: {} vs {}", name, actualSize, expectedSize);
                }
            }

        } catch (IOException e) {
            Files.deleteIfExists(target);
            log.error("[DOWNLOAD] {} | Ошибка: {}", name, e.getMessage());
            throw e;
        }
    }

    /**
     * Скачивает и распаковывает ZIP-архив
     */
    public static void downloadAndExtractZip(String url, Path targetDir, String name) throws IOException {
        Files.createDirectories(targetDir);

        Path tempZip = Files.createTempFile("download_", ".zip");
        log.info("[DOWNLOAD] {} (ZIP)", name);

        try (InputStream in = URI.create(url).toURL().openStream()) {
            Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("[DOWNLOAD] Распаковка {} в {}", name, targetDir);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip.toFile()))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                String fileName = zipEntry.getName();
                // Flatten: все файлы в одну директорию
                String simpleFileName = Paths.get(fileName).getFileName().toString();
                Path newFile = targetDir.resolve(simpleFileName);

                if (!zipEntry.isDirectory()) {
                    Files.createDirectories(newFile.getParent());
                    try (FileOutputStream fos = new FileOutputStream(newFile.toFile())) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }

        Files.deleteIfExists(tempZip);
        log.info("[DOWNLOAD] {} | Распаковано в {}", name, targetDir);
    }

    /**
     * Проверяет размер файла и перекачивает при несоответствии
     */
    public static boolean verifyOrRedownload(String url, Path target, String name) throws IOException {
        if (Files.notExists(target)) {
            return false;
        }

        long expectedSize = getExpectedSize(url);
        if (expectedSize <= 0) {
            // Не можем проверить — считаем что OK если файл не пустой
            return Files.size(target) > 0;
        }

        long actualSize = Files.size(target);
        if (actualSize == expectedSize) {
            log.debug("[DOWNLOAD] {} | Размер OK ({} байт)", name, actualSize);
            return true;
        }

        log.warn("[DOWNLOAD] {} | Размер не совпадает ({} vs {}). Перекачиваем...", name, actualSize, expectedSize);
        downloadWithProgress(url, target, name);
        return true;
    }
}
