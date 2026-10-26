# JDK 21
FROM openjdk:21-jdk-slim

WORKDIR /app

# jar 包
COPY target/*.jar app.jar

# 服务端口 8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]