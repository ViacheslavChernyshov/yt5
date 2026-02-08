# Stage 1: Build Java Application
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Build Whisper.cpp (static)
FROM ubuntu:22.04 AS whisper-build
WORKDIR /build
RUN apt-get update && apt-get install -y git build-essential cmake
RUN git clone https://github.com/ggerganov/whisper.cpp.git
WORKDIR /build/whisper.cpp
RUN cmake -B build -DBUILD_SHARED_LIBS=OFF
RUN cmake --build build --config Release -j

# Stage 3: Build Llama.cpp (static)
FROM ubuntu:22.04 AS llama-build
WORKDIR /build
RUN apt-get update && apt-get install -y git build-essential cmake
RUN git clone https://github.com/ggerganov/llama.cpp.git
WORKDIR /build/llama.cpp
RUN cmake -B build -DGGML_NATIVE=OFF -DBUILD_SHARED_LIBS=OFF
RUN cmake --build build --config Release -j

# Stage 4: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Установка зависимостей (ffmpeg, python для yt-dlp)
RUN apt-get update && apt-get install -y \
    ffmpeg \
    python3 \
    python3-pip \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Установка yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Копируем JAR из стадии сборки
COPY --from=build /app/target/*.jar app.jar

# Копируем Whisper (статически слинкованный)
COPY --from=whisper-build /build/whisper.cpp/build/bin/whisper-cli /usr/local/bin/whisper
RUN chmod +x /usr/local/bin/whisper

# Копируем Llama Server (статически слинкованный)
COPY --from=llama-build /build/llama.cpp/build/bin/llama-server /usr/local/bin/llama-server
RUN chmod +x /usr/local/bin/llama-server

# Создаем директории для данных (модели)
RUN mkdir -p /app/downloads /app/llama /app/whisper

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
