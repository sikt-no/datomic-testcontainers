FROM eclipse-temurin:17.0.5_8-jdk-focal

WORKDIR /opt
RUN apt-get update && apt-get --yes install wget unzip curl postgresql-client
ARG DATOMIC_VERSION=1.0.6733
RUN wget https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip -O datomic-pro.zip
RUN unzip datomic-pro.zip -d /opt
RUN mv /opt/datomic-pro-$DATOMIC_VERSION /opt/datomic-pro

WORKDIR /opt/datomic-pro
RUN mkdir -p /opt/datomic-pro/data && mkdir -p /opt/datomic-pro/log &&  mkdir -p /opt/datomic-pro/config
COPY init transactor-template.properties bin/
ENTRYPOINT "./bin/init" $JAVA_OPTS "-Ddatomic.printConnectionInfo=false", "/opt/datomic-pro/config/transactor.properties"
