# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Source Llama.cpp binaries
FROM ghcr.io/ggml-org/llama.cpp:server AS llama-source

# Stage 3: Source Whisper.cpp binaries
FROM ghcr.io/ggml-org/whisper.cpp:main AS whisper-source

# Stage 4: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Установка зависимостей (ffmpeg, python для yt-dlp)
# Бинарники llama/whisper могут требовать libgomp1 и libcurl4
RUN apt-get update && apt-get install -y \
    ffmpeg \
    python3 \
    python3-pip \
    curl \
    libgomp1 \
    libcurl4 \
    && rm -rf /var/lib/apt/lists/*

# Установка yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Копируем бинарники из официальных образов
COPY --from=llama-source /usr/bin/llama-server /usr/local/bin/llama-server
COPY --from=whisper-source /usr/bin/whisper-cli /usr/local/bin/whisper

RUN chmod +x /usr/local/bin/llama-server /usr/local/bin/whisper

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Создаем директории для данных (модели)
RUN mkdir -p /app/downloads /app/llama /app/whisper

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
