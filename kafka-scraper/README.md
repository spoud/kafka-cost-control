# Kafka Cost Control - AdminClient Scraper

exposes metrics at http://127.0.0.1:8080/q/metrics

## TODOs
 - use separate endpoint for registry

## Build

```bash
mvn clean package -Pnative
```

## Build and push docker image
```bash
docker-compose build
docker-compose push
```
