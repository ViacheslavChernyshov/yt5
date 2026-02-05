# Используем JDK 17
FROM eclipse-temurin:17-jdk-jammy

# Создаём рабочую директорию
WORKDIR /app

# Копируем jar-файл (предварительно собранный через Maven/Gradle)
COPY target/youtubelizer.jar /app/youtubelizer.jar

# Копируем конфиг
COPY config/application.properties /app/config/application.properties

# Экспонируем порты приложения
EXPOSE 8080
EXPOSE 8081

# Команда запуска
ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
