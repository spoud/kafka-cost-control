import { DateTime } from "luxon";

export type AggregatedMetric = {
  startTime: string;
  endTime: string;
  entityType: string;
  name: string;
  initialMetricName: string;
  value: number;
  cost: number | null;
  tags: { [key: string]: string };
  context: { [key: string]: string };
}

export async function loadAggregatedMetrics(
  metricName: string,
  aggType: "sum" | "max" | "avg" | "min" | "count",
  contextToGroupBy: string,
  startTime?: DateTime,
  endTime?: DateTime
) {
  const start = startTime ?? DateTime.utc().minus({ days: 10 }).toISO();
  const end = endTime ?? DateTime.utc().toISO();
  const url = `/api/metrics/${metricName}/${aggType}?startTimestamp=${start}&endTimestamp=${end}&sort=startTime:asc&groupBy=builtin:startTime,builtin:endTime,context:${contextToGroupBy}`;
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to load data: ${response.statusText}`);
  }
  const data = await response.json() as AggregatedMetric[];
  return { data: data.map((d) => ({ value: d.value, label: d.context[contextToGroupBy] ?? "unknown", timestamp: DateTime.fromISO(d.startTime) })) };
}