# Используем JDK 17 с Maven
FROM maven:3.9.2-eclipse-temurin-17 AS build
WORKDIR /app

# Копируем исходники
COPY pom.xml .
COPY src ./src

# Собираем jar
RUN mvn clean package -DskipTests

# Берём готовый jar из build stage
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY --from=build /app/target/youtubelizer.jar /app/youtubelizer.jar

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/youtubelizer.jar"]
