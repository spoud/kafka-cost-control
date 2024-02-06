# Kafka cost control config sample

This node script helps you populate pricing rules and context data easily. It uses the graphql interface of Kafka Cost
Control.

## Requirements

* node 20+

## Usage

Edit the file `context-data.json` and `pricing-rules.json` to match your needs.

Adapt the url, user and password to your environment and run the script. Verbose is optional, it will show you all the
requests.

```bash
node index.js --url=http://localhost:8080/graphql --user=admin --password=kpwx06KsQ2Sbi7Tp2N2l --verbose
```
