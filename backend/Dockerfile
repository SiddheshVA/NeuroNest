# Stage 1: Build using Java 25
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy pom.xml and cache dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and package the executable JAR (skipping unit tests for deployment)
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Lean runtime container
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Create the mandatory persistent upload directory
RUN mkdir -p /data/uploads

# Copy compiled JAR from build stage
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]