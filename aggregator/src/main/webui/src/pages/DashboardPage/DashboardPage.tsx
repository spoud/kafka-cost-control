import { useLoaderData } from "react-router-dom";
import { TimeSeriesBarChart, TimeSeriesDatapoint } from "../../components/TimeSeriesBarChart/TimeSeriesBarChart";
import { loadAggregatedMetrics } from "../../api/metrics-client";
import { Flex } from "@mantine/core";

export type DashboardPageLoaderProps = {
  bytesIn: TimeSeriesDatapoint[];
  bytesOut: TimeSeriesDatapoint[];
}

export async function loader() {
  // perform a request to the given URL, parse the response as a JSON array
  const bytesInData = await loadAggregatedMetrics("kafka_server_brokertopicmetrics_bytesin_total", "sum", "writers");
  const bytesOutData = await loadAggregatedMetrics("kafka_server_brokertopicmetrics_bytesout_total", "sum", "readers");
  return { bytesIn: bytesInData.data, bytesOut: bytesOutData.data };
}

export function DashboardPage() {
  const { bytesIn, bytesOut } = useLoaderData<DashboardPageLoaderProps>();

  return (
    <Flex direction="row" gap="md">
      <TimeSeriesBarChart title="Bytes In Metrics" data={bytesIn} />
      <TimeSeriesBarChart title="Bytes Out Metrics" data={bytesOut} />
    </Flex>
  );
}

export default DashboardPage;
