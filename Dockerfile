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

# Create required directories first (with write permissions for models download)
RUN mkdir -p /app/downloads /app/llama /app/llama/models /app/whisper /app/whisper/models \
    && chmod -R 777 /app

# Install Whisper via Python package (more reliable than binary)
RUN pip3 install openai-whisper

# Download Llama.cpp binary for Linux
RUN cd /tmp && \
    curl -fL https://github.com/ggml-org/llama.cpp/releases/download/b7240/llama-b7240-bin-ubuntu-x64.zip -o llama.zip && \
    unzip -q llama.zip && \
    find . -name "main" -type f -exec cp {} /app/llama/main \; && \
    chmod a+x /app/llama/main && \
    rm -rf /tmp/llama.zip /tmp/artifacts

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Set environment variables for tools and configuration
ENV APP_YTDLP_PATH=/usr/local/bin/yt-dlp \
    APP_FFMPEG_PATH=/usr/bin/ffmpeg \
    APP_DOWNLOAD_PATH=/app/downloads \
    APP_WHISPER_PATH=whisper \
    APP_WHISPER_USE_GPU=false \
    APP_WHISPER_THREADS=4 \
    APP_LLAMA_PATH=/app/llama/main \
    APP_LLAMA_MODEL_PATH=/app/llama/models/qwen2.5-7b-instruct-q3_k_m.gguf \
    APP_LLAMA_SERVER_HOST=localhost \
    APP_LLAMA_SERVER_PORT=8081 \
    APP_LLAMA_THREADS=4

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
