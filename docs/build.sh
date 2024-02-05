#!/bin/bash

docker run -d --name plantuml -p 8081:8080 plantuml/plantuml-server:jetty

export PLANTUML_URL="http://localhost:8081"

asciidoctor -r asciidoctor-plantuml index.adoc

docker rm -f plantuml
