# Contributing Guides

### Pre-requisites
- git
- docker
- maven
- npm

## How to get started

### Aggregator

Go in the folder `aggregator`. Compile using `mvn clean package`. We're using [Quarkus Framework](https://quarkus.io/). If you want to run the development mode, use `mvn quarkus:dev`.

### Kafka scraper

Go in the folder `kafka-scraper`. Compile using `mvn clean package`. We're using [Quarkus Framework](https://quarkus.io/). If you want to run the development mode, use `mvn quarkus:dev`.

### Frontend

Go in the folder `frontend`. Compile using `npm install`. We're using the [Angular Framework](https://angular.dev/). Run in dev mode using `npm start`.

### Kafka Connect

Go the folder `kafka-connect`. Compile using `docker compose build`. We're using [Kafka Connect](https://kafka.apache.org/documentation/#connect).

## Contributing to this repo

Bug reports and feature requests are welcome. Please fill up a GitHub issue.

Before contributing to a code change, please discuss your idea with the maintainers in an issue.

Every change should be made with a pull request. First fork the repos, create a branch on our repos and create a pull request on this repository. Put the [code owners](./CODEOWNERS) as reviewers.

## Releases

Releases are made through git tags using semantic versioning. Where `MAJOR.MINOR.PATH`:
- `MAJOR` version when you make incompatible API changes,
- `MINOR` version when you add functionality in a backwards compatible manner, and
- `PATCH` version when you make backwards compatible bug fixes or dependencies update.

To create a release you should push a tag to the master branch. For example
```bash
git tag 0.1.2
git push --tags
```
