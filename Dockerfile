FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Получаем имя jar через build-arg
ARG JAR_FILE
COPY ${JAR_FILE} /app/youtubelizer.jar

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
