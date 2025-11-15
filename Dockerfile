# --- ETAPA 1: CONSTRUCCIÓN (Builder) ---
# Usamos una imagen que contiene Maven y Java 17 (Temurin es el sucesor de OpenJDK)
FROM maven:3.9-eclipse-temurin-17 AS builder

# Establecemos el directorio de trabajo dentro del contenedor
WORKDIR /app

# 1. Copia solo el pom.xml para descargar las dependencias
# Esto aprovecha el caché de Docker. Si el pom.xml no cambia,
# no se volverán a descargar las dependencias.
COPY pom.xml .
RUN mvn dependency:go-offline

# 2. Copia el resto del código fuente
COPY src ./src

# 3. Compila la aplicación y la empaqueta en un .jar
# Omitimos los tests para un build más rápido
RUN mvn package -DskipTests

# --- ETAPA 2: EJECUCIÓN (Runner) ---
# Usamos una imagen JRE (Java Runtime) mínima basada en Alpine.
# Es mucho más pequeña que un JDK completo.
FROM eclipse-temurin:17-jre-alpine

# Establecemos el directorio de trabajo
WORKDIR /app

# ❗️ IMPORTANTE: Revisa esta línea ❗️
# Copia el .jar construido de la etapa 'builder' a esta imagen.
# Asegúrate de que el nombre del .jar coincida con el <artifactId> y <version>
# de tu archivo pom.xml.
#
# (El pom.xml que te di generaría 'telegram-guia-0.0.1-SNAPSHOT.jar')
COPY --from=builder /app/target/telegram-guia-0.0.1-SNAPSHOT.jar app.jar

# 4. Comando para ejecutar el bot
# Spring Boot leerá las variables de entorno (TELEGRAM_BOT_TOKEN, etc.)
# que le pasará el servicio de deploy (Render).
ENTRYPOINT ["java", "-jar", "app.jar"]