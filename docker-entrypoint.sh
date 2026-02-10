#!/bin/bash
set -e

echo "=========================================="
echo "YouTubeLizer Docker Container Startup"
echo "=========================================="

echo ""
echo "[1/3] Checking external tools..."
echo "  - yt-dlp: $(which yt-dlp 2>/dev/null || echo 'NOT FOUND')"
echo "  - ffmpeg: $(which ffmpeg 2>/dev/null || echo 'NOT FOUND')"
echo "  - whisper-cli: ${APP_WHISPER_PATH:-/app/whisper/whisper-cli}"
echo "  - java: $(java -version 2>&1 | head -1)"

echo ""
echo "[2/3] Verifying whisper.cpp..."

WHISPER_PATH="${APP_WHISPER_PATH:-/app/whisper/whisper-cli}"
MODEL_PATH="${APP_WHISPER_MODEL_PATH:-/app/whisper/ggml-large-v3.bin}"

if [ -x "$WHISPER_PATH" ]; then
    echo "  ✅ whisper-cli binary found: $WHISPER_PATH"
    # Check for missing shared libraries
    MISSING_LIBS=$(ldd "$WHISPER_PATH" 2>&1 | grep "not found" || true)
    if [ -n "$MISSING_LIBS" ]; then
        echo "  ⚠️  Missing shared libraries:"
        echo "$MISSING_LIBS" | sed 's/^/    /'
    else
        echo "  ✅ All shared libraries resolved"
    fi
else
    echo "  ❌ whisper-cli binary NOT found at: $WHISPER_PATH"
fi

if [ -f "$MODEL_PATH" ]; then
    MODEL_SIZE=$(du -sh "$MODEL_PATH" | cut -f1)
    echo "  ✅ Model found: $MODEL_PATH ($MODEL_SIZE)"
else
    echo "  ❌ Model NOT found at: $MODEL_PATH"
fi

# Check GPU availability
if command -v nvidia-smi &> /dev/null; then
    echo "  🎮 GPU detected:"
    nvidia-smi --query-gpu=name,memory.total --format=csv,noheader 2>/dev/null || echo "  ⚠️  nvidia-smi failed"
else
    echo "  ℹ️  No NVIDIA GPU detected, will use CPU mode"
fi

echo ""
echo "[3/4] Verifying llama.cpp..."
LLAMA_PATH="${APP_LLAMA_PATH:-/app/llama/llama-server}"
LLAMA_MODEL="${APP_LLAMA_MODEL_PATH:-/app/llama/models/qwen2.5-7b-instruct-q3_k_m.gguf}"

if [ -x "$LLAMA_PATH" ]; then
    echo "  ✅ llama-server binary found: $LLAMA_PATH"
    # Check for missing shared libraries
    MISSING_LIBS=$(ldd "$LLAMA_PATH" 2>&1 | grep "not found" || true)
    if [ -n "$MISSING_LIBS" ]; then
        echo "  ⚠️  Missing shared libraries for llama-server:"
        echo "$MISSING_LIBS" | sed 's/^/    /'
    else
        echo "  ✅ All shared libraries resolved for llama-server"
    fi
else
    echo "  ❌ llama-server binary NOT found at: $LLAMA_PATH"
fi

if [ -f "$LLAMA_MODEL" ]; then
    MODEL_SIZE=$(du -sh "$LLAMA_MODEL" | cut -f1)
    echo "  ✅ Llama model found: $LLAMA_MODEL ($MODEL_SIZE)"
else
    echo "  ❌ Llama model NOT found at: $LLAMA_MODEL"
fi

echo ""
echo "[3/3] Starting YouTubeLizer application..."
echo "  Server: http://localhost:8080"
echo "=========================================="
echo ""

# Execute the main application
exec java "$@"
