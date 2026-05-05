FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
# Resolve dependencies before copying source — cached unless pom.xml changes
RUN apk add --no-cache maven && mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Tesseract OCR (kor+eng) — needed when app.image-description.ocr-enabled=true
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-kor
# LibreOffice (WMF→PNG) is large (~500 MB); add it in a custom image if
# docx-wmf-convert=true: RUN apk add --no-cache libreoffice
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/data/documents /app/data/images
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
