FROM eclipse-temurin:21-jre-alpine

# Run as an unprivileged user: a container process that escapes the JVM should not
# land as root.
RUN addgroup -S app && adduser -S -G app -h /app app

WORKDIR /app
COPY --chown=app:app target/*.jar app.jar
USER app

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
