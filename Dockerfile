# =========================================================================================
# Dockerfile Multistage - Microservicio Motor de Reversos Quarkus + Java 21 LTS (Non-Root PCI-DSS)
# =========================================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="xpos-ms-motor-reversos" \
      org.opencontainers.image.description="Motor Genérico de Transacciones y Servicio de Reversos POS en ARO" \
      org.opencontainers.image.vendor="XPOS Architecture"

# Crear usuario sin privilegios (PCI-DSS / OpenShift SecurityContextConstraints restricted-v2)
RUN addgroup -g 1001 -S xpos && adduser -u 1001 -S xpos -G xpos
WORKDIR /deployments

COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=1001:1001 /workspace/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 1001
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
