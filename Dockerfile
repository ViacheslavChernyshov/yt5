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

# Copy Llama model from build context if it exists
COPY llama/models/* /app/llama/models/

# Install Whisper via Python package (more reliable than binary)
RUN pip3 install openai-whisper

# Pre-download Whisper model to cache in the runtime image
# This ensures the model is available in the final container image
# The volume mount in docker-compose will preserve this between container restarts
RUN echo "Pre-downloading Whisper large-v3 model (this takes ~2-4 minutes)..." && \
    mkdir -p /app/whisper && \
    mkdir -p /root/.cache/whisper && \
    python3 -c "import whisper; print('Loading model...'); model = whisper.load_model('large-v3'); print('✅ Model loaded and cached successfully')"

# Try to download Llama.cpp binary, but don't fail if it doesn't work
# The application can still run if llama binary is missing (graceful degradation)
RUN mkdir -p /tmp/llama_extract && cd /tmp/llama_extract && \
    echo "Attempting to download Llama.cpp binary..." && \
    if curl -fSL --max-time 120 --retry 2 https://github.com/ggml-org/llama.cpp/releases/download/b7240/llama-b7240-bin-ubuntu-x64.zip -o llama.zip; then \
        echo "Downloaded successfully, extracting..."; \
        if unzip -q llama.zip 2>/dev/null; then \
            echo "Extracted, searching for executable..."; \
            if find . -type f -executable ! -name "*.so*" -print | head -1 | xargs -I {} cp {} /app/llama/main 2>/dev/null; then \
                chmod a+x /app/llama/main; \
                echo "Llama binary installed successfully"; \
            else \
                echo "Warning: No executable found in archive, skipping binary installation"; \
            fi; \
        else \
            echo "Warning: Failed to extract llama.zip"; \
        fi; \
    else \
        echo "Warning: Failed to download Llama binary, will use CPU-only mode"; \
    fi; \
    rm -rf /tmp/llama_extract

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Copy startup script for initialization
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

# Set environment variables for tools and configuration
ENV APP_YTDLP_PATH=/usr/local/bin/yt-dlp \
    APP_FFMPEG_PATH=/usr/bin/ffmpeg \
    APP_DOWNLOAD_PATH=/app/downloads \
    APP_WHISPER_PATH=whisper \
    APP_WHISPER_USE_GPU=false \
    APP_WHISPER_THREADS=4 \
    HF_HOME=/app/whisper \
    WHISPER_CACHE=/app/whisper \
    APP_LLAMA_PATH=/app/llama/main \
    APP_LLAMA_MODEL_PATH=/app/llama/models/qwen2.5-7b-instruct-q3_k_m.gguf \
    APP_LLAMA_SERVER_HOST=localhost \
    APP_LLAMA_SERVER_PORT=8081 \
    APP_LLAMA_THREADS=4

EXPOSE 8080

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["-jar", "app.jar"]
