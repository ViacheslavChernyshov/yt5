# Dockerfile
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Копируем jar, который соберет GitHub Actions
COPY target/youtubelizer.jar /app/youtubelizer.jar

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
