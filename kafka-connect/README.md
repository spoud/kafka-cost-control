# Kafka cost control kafka connect

This folder helps you build a custom image for kafka connect with the connectors you want to use.

By default, it only includes the JDBC connector since we use TimescaleDB (based on Postgresql) as sink database.

## Build and push the image

```shell
docker-compose build connect
docker-compose push connect
```
