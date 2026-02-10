# =============================================================================
# Stage 1: Build whisper.cpp with CUDA support
# =============================================================================
FROM nvidia/cuda:12.4.0-devel-ubuntu22.04 AS build-whisper

WORKDIR /build

RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    && rm -rf /var/lib/apt/lists/*

# Clone whisper.cpp - this layer will be cached
RUN git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git .

# Use CUDA stubs for linking (fixes libcuda.so.1 not found)
RUN ln -s /usr/local/cuda/lib64/stubs/libcuda.so /usr/local/cuda/lib64/stubs/libcuda.so.1 \
    && echo "/usr/local/cuda/lib64/stubs" > /etc/ld.so.conf.d/z-cuda-stubs.conf \
    && ldconfig


# Configure CMake - this layer will be cached
RUN cmake -B build \
    -DGGML_CUDA=1 \
    -DCMAKE_CUDA_ARCHITECTURES="50" \
    -DCMAKE_BUILD_TYPE=Release

# Build whisper-cli - this is the long step
# If interrupted, you only restart this step, not the clone/config
RUN cmake --build build --config Release -j$(nproc) --target whisper-cli

# Collect all shared libraries into a single flat directory for easy COPY
# (Docker COPY globs don't recurse into subdirectories)
RUN mkdir -p /build/collected_libs \
    && find /build/build -name '*.so' -o -name '*.so.*' | while read f; do cp -P "$f" /build/collected_libs/; done \
    && ls -la /build/collected_libs/

# =============================================================================
# Stage 2: Build Llama.cpp with CUDA support
# =============================================================================
FROM nvidia/cuda:12.4.0-devel-ubuntu22.04 AS build-llama

WORKDIR /build

RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    curl \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Clone llama.cpp
# Using a specific tag or commit hash is often safer for reproducibility, 
# but for now we'll use master/latest to get recent fixes
RUN git clone https://github.com/ggml-org/llama.cpp.git .

# Use CUDA stubs for linking (fixes libcuda.so.1 not found)
RUN ln -s /usr/local/cuda/lib64/stubs/libcuda.so /usr/local/cuda/lib64/stubs/libcuda.so.1 \
    && echo "/usr/local/cuda/lib64/stubs" > /etc/ld.so.conf.d/z-cuda-stubs.conf \
    && ldconfig

# Configure CMake
RUN cmake -B build \
    -DGGML_CUDA=1 \
    -DCMAKE_CUDA_ARCHITECTURES="50;61;70;75;80;86;89" \
    -DCMAKE_BUILD_TYPE=Release

# Build llama-server
RUN cmake --build build --config Release -j$(nproc) --target llama-server

# Collect all shared libraries into a single flat directory
RUN mkdir -p /build/collected_libs \
    && find /build/build -name '*.so' -o -name '*.so.*' | while read f; do cp -P "$f" /build/collected_libs/; done \
    && ls -la /build/collected_libs/

# Download Qwen2.5-7B-Instruct-Q3_K_M GGUF model (~3.5GB)
RUN mkdir -p /build/models && \
    echo "Downloading Qwen2.5-7B-Instruct-Q3_K_M model..." && \
    wget -q --show-progress -O /build/models/qwen2.5-7b-instruct-q3_k_m.gguf \
    "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q3_k_m.gguf" && \
    echo "Model downloaded successfully"

# =============================================================================
# Stage 3: Build Java Application
# =============================================================================
FROM maven:3.9.6-eclipse-temurin-21 AS build-java

WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# =============================================================================
# Stage 4: Runtime
# =============================================================================
FROM nvidia/cuda:12.4.0-runtime-ubuntu22.04

WORKDIR /app

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    ffmpeg \
    curl \
    unzip \
    libgomp1 \
    libcurl4 \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Install Java 21 JRE
RUN apt-get update && apt-get install -y \
    software-properties-common \
    && add-apt-repository -y ppa:openjdk-r/ppa \
    && apt-get update && apt-get install -y \
    openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Install yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

# Create required directories
RUN mkdir -p /app/downloads /app/llama /app/llama/models /app/whisper /app/temp \
    && chmod -R 777 /app

# --- Whisper.cpp setup ---
# Copy whisper-cli binary
COPY --from=build-whisper /build/build/bin/whisper-cli /app/whisper/whisper-cli
RUN chmod a+x /app/whisper/whisper-cli

# Copy whisper libs
COPY --from=build-whisper /build/collected_libs/ /usr/local/lib/

# Download whisper model
RUN echo "Downloading whisper large-v3 GGML model (~3GB)..." && \
    wget -q --show-progress -O /app/whisper/ggml-large-v3.bin \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin" && \
    echo "Model downloaded successfully"

# --- Llama.cpp setup ---
# Copy llama-server binary
COPY --from=build-llama /build/build/bin/llama-server /app/llama/llama-server
RUN chmod a+x /app/llama/llama-server

# Copy llama libs (merging with whisper libs in /usr/local/lib)
COPY --from=build-llama /build/collected_libs/ /usr/local/lib/

# Copy Llama model
COPY --from=build-llama /build/models/qwen2.5-7b-instruct-q3_k_m.gguf /app/llama/models/

# Update linker cache
RUN ldconfig

# Copy JAR from Java build stage
COPY --from=build-java /app/target/*.jar app.jar

# Copy startup script
COPY docker-entrypoint.sh /app/
RUN chmod +x /app/docker-entrypoint.sh

# Environment variables
ENV APP_YTDLP_PATH=/usr/local/bin/yt-dlp \
    APP_FFMPEG_PATH=/usr/bin/ffmpeg \
    APP_DOWNLOAD_PATH=/app/downloads \
    APP_WHISPER_PATH=/app/whisper/whisper-cli \
    APP_WHISPER_MODEL_PATH=/app/whisper/ggml-large-v3.bin \
    APP_WHISPER_USE_GPU=true \
    APP_WHISPER_GPU_DEVICE=0 \
    APP_WHISPER_THREADS=4 \
    APP_WHISPER_BEAM_SIZE=5 \
    APP_WHISPER_BEST_OF=5 \
    APP_LLAMA_PATH=/app/llama/main \
    APP_LLAMA_MODEL_PATH=/app/llama/models/qwen2.5-7b-instruct-q3_k_m.gguf \
    APP_LLAMA_SERVER_HOST=localhost \
    APP_LLAMA_SERVER_PORT=8081 \
    APP_LLAMA_THREADS=2 \
    NVIDIA_VISIBLE_DEVICES=all \
    NVIDIA_DRIVER_CAPABILITIES=compute,utility

EXPOSE 8080

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["-jar", "app.jar"]
