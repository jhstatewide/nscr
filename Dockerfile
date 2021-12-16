FROM ubuntu:20.04
ARG http_proxy=""
RUN apt-get -yq update && apt-get install -yq openjdk-11-jdk
# add a user named 'app'
RUN adduser --disabled-password --gecos "" app
COPY --chown=app gradle/ /home/app/gradle/
COPY --chown=app build.gradle.kts gradle.properties gradlew settings.gradle.kts /home/app/
COPY --chown=app src/ /home/app/src/
WORKDIR /home/app
USER app
RUN ls -la /home/app
RUN ./gradlew build