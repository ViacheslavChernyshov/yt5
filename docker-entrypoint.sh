#!/bin/bash
set -e

echo "=========================================="
echo "YouTubeLizer Docker Container Startup"
echo "=========================================="

# Set environment variables for Whisper cache
export HF_HOME=${HF_HOME:-/app/whisper}
export WHISPER_CACHE=${WHISPER_CACHE:-/app/whisper}
mkdir -p "$HF_HOME"

echo ""
echo "[1/3] Checking external tools..."
echo "  - yt-dlp: $(which yt-dlp)"
echo "  - ffmpeg: $(which ffmpeg)"
echo "  - python3: $(which python3)"

echo ""
echo "[2/3] Pre-loading Whisper model to cache..."
echo "  Cache location: $HF_HOME"
echo "  This may take 2-4 minutes on first startup..."

# Try to pre-load the Whisper model
if python3 -c "import whisper; print('Loading model...'); model = whisper.load_model('large-v3'); print('✅ Whisper model loaded successfully')" 2>&1; then
    echo "  ✅ Whisper model is ready"
else
    echo "  ⚠️  Whisper model pre-load failed, will attempt to load on first request"
fi

echo ""
echo "[3/3] Starting YouTubeLizer application..."
echo "  Server: http://localhost:8080"
echo "=========================================="
echo ""

# Execute the main application
exec java "$@"
