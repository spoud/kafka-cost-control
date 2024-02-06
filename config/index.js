import {parseArgs} from "node:util";
import fs from "node:fs";
import {Buffer} from "node:buffer";

const queries = fs.readFileSync('queries.graphql', 'utf8');
const pricing_rules = JSON.parse(fs.readFileSync('pricing-rules.json', 'utf8'));
const context_data = JSON.parse(fs.readFileSync('context-data.json', 'utf8'));

const {values} = parseArgs({
    strict: true,
    options: {
        url: {
            type: "string",
            short: "u",
            default: "http://localhost:8080/graphql"
        },
        user: {
            type: "string"
        },
        password: {
            type: "string",
        },
        verbose: {
            type: "boolean",
            short: "v",
            default: false
        },
        help: {
            type: "boolean",
            short: "h",
            default: false
        }
    },
});

function printUsageAndExit() {
    console.log("Usage: node index.js --url <url-including-graph-path> --user <username> --password <password> --verbose");
    process.exit(1);
}

function printVerbose(message, ...args) {
    if (values.verbose) {
        console.log(message, ...args);
    }
}

function printNormal(message, ...args) {
    console.log(message, ...args);
}

if (values.help) {
    printUsageAndExit();
}

if (!values.user) {
    console.error("user is required");
    printUsageAndExit();
}
if (!values.password) {
    console.error("password is required");
    printUsageAndExit();
}


const host = values.url
const basicAuth = Buffer.from(`${values.user}:${values.password}`).toString('base64');

printNormal('using host:', host);

async function sendRequest(operationName, variables) {
    return fetch(host, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": "Basic " + basicAuth
        },
        body: JSON.stringify({operationName, query: queries, variables}),
    })
            .then(r => r.json())
            .then(data => printVerbose(JSON.stringify(data, null, 2)))
}

async function updatePricingRule() {
    for (const row of pricing_rules) {
        printVerbose('updating pricing rule for the metric', row.metricName);
        await sendRequest("saveRule", row);
        printVerbose('Metric updated', row.metricName);
    }
    printNormal('done updating pricing rules');
    if (values.verbose) {
        return sendRequest("pricingRules", {});
    }
}

async function updateContextdata() {
    for (const row of context_data) {
        printVerbose('updating context data with id', row.id);
        await sendRequest("saveContextData", row);
        printVerbose('Context data updated', row.id);
    }
    printNormal('done updating context data');
    if (values.verbose) {
        return sendRequest("contextData", {});
    }
}

async function main() {
    await updatePricingRule();
    await updateContextdata();
}

// Run the main function
main().then(r => printNormal('done'));
