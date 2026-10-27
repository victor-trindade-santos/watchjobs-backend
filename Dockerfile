# Imagem da API: o jar é compilado no CI (./gradlew bootJar); aqui só entra o jar pronto.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S watchjobs && adduser -S watchjobs -G watchjobs
WORKDIR /app
COPY build/libs/app.jar app.jar
USER watchjobs

# Pouca memória (512 MB no Cloud Run): heap proporcional, serial GC e partida mais rápida.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -Duser.timezone=UTC"
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
