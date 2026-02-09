#!/bin/bash
# YouTubeLizer - Quick Setup & Test

echo "=========================================="
echo "YouTubeLizer - Быстрая настройка"
echo "=========================================="
echo ""

# Check Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker не установлен"
    exit 1
fi

echo "✅ Docker найден"

# Build with model preload
echo ""
echo "🔨 Собираем образ с предзагруженной моделью Whisper..."
echo "   (первая сборка займет 5-10 минут)"
docker-compose build

if [ $? -ne 0 ]; then
    echo "❌ Ошибка при сборке образа"
    exit 1
fi

echo "✅ Образ собран успешно"

# Start services
echo ""
echo "🚀 Запускаем сервисы..."
docker-compose up -d

if [ $? -ne 0 ]; then
    echo "❌ Ошибка при запуске сервисов"
    exit 1
fi

echo "✅ Сервисы запущены"

# Wait for app to be ready
echo ""
echo "⏳ Ожидание загрузки приложения (до 2 минут)..."
for i in {1..120}; do
    if curl -s http://localhost:8080/health > /dev/null 2>&1; then
        echo "✅ Приложение готово!"
        break
    fi
    if [ $((i % 10)) -eq 0 ]; then
        echo -n "."
    fi
    sleep 1
done

echo ""
echo "=========================================="
echo "🎉 YouTubeLizer готов к работе!"
echo "=========================================="
echo ""
echo "📱 Telegram Bot: Отправьте /start боту @YouTubeLizer_bot"
echo "📊 Веб интерфейс: http://localhost:8080"
echo "📚 Документация: https://github.com/youruserame/YouTubeLizer"
echo ""
echo "📖 Логи:"
echo "   docker-compose logs -f app"
echo ""
echo "🛑 Остановка:"
echo "   docker-compose down"
echo ""
