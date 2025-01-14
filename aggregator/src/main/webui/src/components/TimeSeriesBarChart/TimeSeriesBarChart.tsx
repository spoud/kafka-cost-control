import { Paper } from "@mantine/core";
import { useElementSize } from "@mantine/hooks";
import { DateTime } from "luxon";
import { Datum, PlotData as PlotlyData } from "plotly.js";
import { useMemo } from "react";
import Plot from "react-plotly.js";
import ColorHash from "color-hash";

const colorHash = new ColorHash({
  hue: { min: 0, max: 360 },
  saturation: [0.6, 0.9],
  lightness: [0.4, 0.7],
});

export type TimeSeriesDatapoint = {
    value: number;
    label: string;
    timestamp: DateTime;
};

export type TimeSeriesBarChartProps = {
    title: string;
    data: TimeSeriesDatapoint[];
};

export function TimeSeriesBarChart({ title, data }: TimeSeriesBarChartProps) {
    const { ref, width, height } = useElementSize();

    const traces = useMemo(() => {
        const t = {} as { [label: string]: PlotlyData };
        for (const dp of data) {
            const label = dp.label;
            const timestamp = dp.timestamp;
            const trace = t[label] ?? {
                x: [] as Datum[],
                y: [] as Datum[],
                name: label,
                type: "bar",
                marker: { color: colorHash.hex(label) },
            };
            (trace.x as Datum[]).push(timestamp.toISO() as Datum);
            (trace.y as Datum[]).push(dp.value as Datum);
            t[label] = trace;
        }
        return Object.values(t).sort((a, b) => a.name.localeCompare(b.name));
    }, [data]);

    console.log(traces);
    return (
        <Paper shadow="md" p="sm" ref={ref} flex={1} style={{ maxHeight: 400, aspectRatio: "2/1" }}>
            <Plot
                data={traces}
                layout={{
                    width: width,
                    height: height,
                    title: { text: title },
                    bargap: 0.03,
                    barmode: "stack",
                    barnorm: "percent",
                }}
            />
        </Paper>
    );
}
