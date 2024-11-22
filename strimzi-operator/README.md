# kafka-cost-control-strimzi-operator

This is a Kubernetes Operator that watches Strimzi's `KafkaUser` and `KafkaTopic` resources and generates context for
cost control based on the annotations of those resources.

For example, if a `KafkaTopic` has the annotation `spoud.io/kcc-context.team=amazing-anteaters`, the operator will
generate a context object that contains the `team=amazing-anteaters` key-value pair, whose regex matches the name
of the `KafkaTopic`.
All key-value pairs that are prefixed with `spoud.io/kcc-context.` are considered to be part of the context and will
be included in the generated context object. The prefix itself is configurable in the operator config.

Whenever a `KafkaUser` changes, the operator goes over the permissions of that user and re-generates the context for
topics that the user has access to. For example, if users belonging to teams `amazing-anteaters` and `brilliant-badgers`
have `READ` access to the same topic, then that topic's context will have a `readers=amazing-anteaters,brilliant-badgers`
key-value pair. This pair will then make it possible to distribute the costs of outgoing traffic from the topic.

The operator knows which team/project/group/application a `KafkaUser` belongs to by looking at the user's
`spoud.io/kcc-context.application` annotation.

The latter annotation is freely configurable in the operator config (see `OperatorConfig.java`).

## Running Locally

The operator will run against the Kubernetes cluster that your `kubectl` is currently configured to use.
So make sure that you are connected to the correct cluster.
The operator will use your kubeconfig's permissions to watch the `KafkaUser` and `KafkaTopic` resources.
So make sure that you have the necessary permissions to watch those resources.
Lastly, make sure that the aforementioned custom resources are present in the cluster (i.e. Strimzi is installed).
Then run:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/kafka-cost-control-strimzi-operator-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- SmallRye GraphQL Client ([guide](https://quarkus.io/guides/smallrye-graphql-client)): Create GraphQL Clients
- Operator SDK ([guide](https://docs.quarkiverse.io/quarkus-operator-sdk/dev/index.html)): Quarkus extension for the Java Operator SDK (https://javaoperatorsdk.io)
