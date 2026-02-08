# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Установка зависимостей (ffmpeg, python для yt-dlp)
# Также ставим libgomp1 и libcurl4 для работы бинарников llama/whisper
RUN apt-get update && apt-get install -y \
    ffmpeg \
    python3 \
    python3-pip \
    curl \
    unzip \
    libgomp1 \
    libcurl4 \
    && rm -rf /var/lib/apt/lists/*

# Установка yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Создаем директории для данных (модели и бинарники)
# Права на запись важны, так как Java будет скачивать сюда файлы
RUN mkdir -p /app/downloads /app/llama /app/whisper && chmod -R 777 /app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
