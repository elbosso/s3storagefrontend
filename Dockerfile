#Build stage
FROM maven:3.6.1-jdk-11 AS build-env

ADD . /s3storagefrontend

WORKDIR s3storagefrontend

RUN mvn -U compile package assembly:single

# Run it
FROM openjdk:11

COPY --from=build-env /s3storagefrontend/target/*-jar-with-dependencies.jar /app/s3storagefrontend.jar

EXPOSE 7000

CMD ["java", "-jar", "/app/s3storagefrontend.jar"]