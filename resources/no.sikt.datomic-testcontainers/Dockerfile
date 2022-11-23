FROM eclipse-temurin:17.0.5_8-jdk-focal

ARG DATOMIC_HTTP_PASSWORD
ARG DATOMIC_HTTP_USERNAME
ARG DATOMIC_VERSION=1.0.6527
WORKDIR /opt
RUN apt-get update && apt-get --yes install wget unzip curl postgresql-client
RUN wget --http-user=$DATOMIC_HTTP_USERNAME --http-password=$DATOMIC_HTTP_PASSWORD https://my.datomic.com/repo/com/datomic/datomic-pro/$DATOMIC_VERSION/datomic-pro-$DATOMIC_VERSION.zip -O datomic-pro.zip
RUN unzip datomic-pro.zip -d /opt
RUN mv /opt/datomic-pro-$DATOMIC_VERSION /opt/datomic-pro

WORKDIR /opt/datomic-pro
RUN mkdir -p /opt/datomic-pro/data && mkdir -p /opt/datomic-pro/log &&  mkdir -p /opt/datomic-pro/config
COPY init transactor-template.properties bin/
ENTRYPOINT "./bin/init" $JAVA_OPTS "-Ddatomic.printConnectionInfo=false", "/opt/datomic-pro/config/transactor.properties"
