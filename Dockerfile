# === Stage 1: Build ===
FROM maven:3.9.2-eclipse-temurin-17 AS build
WORKDIR /app

# Копируем pom и исходники
COPY pom.xml .
COPY src ./src

# Собираем jar (без тестов)
RUN mvn clean package -DskipTests

# === Stage 2: Runtime ===
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Копируем готовый jar из предыдущего stage
COPY --from=build /app/target/youtubelizer.jar /app/youtubelizer.jar

# Экспонируем порты приложения
EXPOSE 8080
EXPOSE 8081

# Запуск приложения
ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
