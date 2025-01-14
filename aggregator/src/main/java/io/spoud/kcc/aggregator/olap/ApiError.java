package io.spoud.kcc.aggregator.olap;

public record ApiError(String message, ApiError innerError) {
}
