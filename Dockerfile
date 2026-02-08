# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy
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

# Set environment variables to point to the correct paths
# These will be consumed by the Spring application
ENV APP_YTDLP_PATH=/usr/local/bin/yt-dlp
ENV APP_FFMPEG_PATH=/usr/bin/ffmpeg
ENV APP_DOWNLOAD_PATH=/app/downloads
ENV APP_WHISPER_PATH=./whisper/whisper-cli
ENV APP_WHISPER_MODEL_PATH=./whisper/models/ggml-large-v3.bin
ENV APP_WHISPER_USE_GPU=false
ENV APP_WHISPER_THREADS=4
ENV APP_LLAMA_PATH=./llama/main
ENV APP_LLAMA_MODEL_PATH=./llama/models/qwen2.5-7b-instruct-q3_k_m.gguf
ENV APP_LLAMA_SERVER_PORT=8081
ENV APP_LLAMA_THREADS=4

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
