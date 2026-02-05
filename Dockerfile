# Dockerfile
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Копируем jar и конфиг из корня репозитория
COPY target/youtubelizer.jar /app/youtubelizer.jar
COPY application.properties /app/config/application.properties

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
