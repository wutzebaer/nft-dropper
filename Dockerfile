FROM adoptopenjdk/openjdk11:x86_64-ubuntu-jre11u-2022-01-20-19-54-beta-nightly
RUN apt-get update && apt-get install -y \
  docker.io \
  && rm -rf /var/lib/apt/lists/*

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
#-v /var/run/docker.sock:/var/run/docker.sock