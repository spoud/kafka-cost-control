const fs = require('fs');
const query = fs.readFileSync('queries.graphql', 'utf8');
const pricing_rules = JSON.parse(fs.readFileSync('pricing-rules.json', 'utf8'));

const host = "http://localhost:8080/graphql";

async function sendRequest(operationName, variables) {
    return fetch(host, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": "Basic YWRtaW46a3B3eDA2S3NRMlNiaTdUcDJOMmw="
        },
        body: JSON.stringify({operationName, query, variables}),
    })
        .then(r => r.json())
        .then(data => console.log(JSON.stringify(data, null, 2)))
}


pricing_rules.forEach(async row => {
    console.log('updating rule for the metric ', row.metricName);
    await sendRequest("saveRule", row);
});

console.log('done updating');
sendRequest("pricingRules", {});
