# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Установка зависимостей (ffmpeg, python для yt-dlp, unzip для распаковки)
RUN apt-get update && apt-get install -y \
    ffmpeg \
    python3 \
    python3-pip \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Установка yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Скачиваем готовый llama.cpp (llama-server) из GitHub Releases
RUN curl -L https://github.com/ggerganov/llama.cpp/releases/latest/download/llama-b4869-bin-ubuntu-x64.zip -o /tmp/llama.zip \
    && unzip /tmp/llama.zip -d /tmp/llama \
    && cp /tmp/llama/build/bin/llama-server /usr/local/bin/llama-server \
    && chmod +x /usr/local/bin/llama-server \
    && rm -rf /tmp/llama.zip /tmp/llama

# Скачиваем готовый whisper.cpp из GitHub Releases
RUN curl -L https://github.com/ggerganov/whisper.cpp/releases/latest/download/whisper-blas-bin-x64.zip -o /tmp/whisper.zip \
    && unzip /tmp/whisper.zip -d /tmp/whisper \
    && cp /tmp/whisper/whisper-cli /usr/local/bin/whisper \
    && chmod +x /usr/local/bin/whisper \
    && rm -rf /tmp/whisper.zip /tmp/whisper

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Создаем директории для данных (модели)
RUN mkdir -p /app/downloads /app/llama /app/whisper

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
