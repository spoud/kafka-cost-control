import { Component, computed, inject, input, output, signal } from '@angular/core';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { EChartsCoreOption, EChartsType } from 'echarts/core';
import { saveAs } from 'file-saver-es';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { Panel } from '../../../tab-reporting/panel.type';
import { Maybe, MetricHistory, Scalars } from '../../../../generated/graphql/types';
import { ThemeService } from '../../../services/theme.service';
import { CHART_COLORS_DARK, CHART_COLORS_LIGHT } from '../../../services/chart-theme';
import {
    ChartLegendComponent,
    ChartLegendItem,
    LegendClick,
    LegendSort,
} from '../chart-legend/chart-legend.component';
import { applyLegendClick } from '../legend-selection';
import { ChartActions } from '../chart-actions';
import { formatCompact, formatPercent } from '../../../common/compact-number';
import { DateRange } from '../../../common/date-range-quick-select/date-range-quick-select.component';

export type BarOrLine = 'bar' | 'line';

@Component({
    selector: 'app-bar-chart',
    imports: [
        NgxEchartsDirective,
        MatIconButton,
        MatIcon,
        MatMenu,
        MatMenuItem,
        MatMenuTrigger,
        ChartLegendComponent,
    ],
    templateUrl: './bar-chart.component.html',
    styleUrls: ['./bar-chart.component.scss'],
    providers: [provideEchartsCore({ echarts })],
})
export class BarChartComponent implements ChartActions {
    /** Hosts that surface these actions in their own menu (Reporting panels) turn this off. */
    showToolbar = input(true);

    protected readonly themeService = inject(ThemeService);

    chartInit = output<EChartsType>();

    normalized = signal(false);

    legendSort = signal<LegendSort>('value');

    /** The legend column costs real width; hiding it gives the plot the whole card. */
    showLegend = signal(true);

    /**
     * Whether to bridge a genuine hole in a series. Off by default: a straight line across a
     * period with no measurements reads as "steady" when the truth is "unknown".
     */
    connectNulls = signal(false);

    deselected = signal<ReadonlySet<string>>(new Set());

    metricsData = input.required<MetricHistory[]>();

    panelData = input<Panel>();

    type = input.required<BarOrLine>();

    /**
     * The range the user actually asked for. Without it the time axis spans only the timestamps
     * present in the response, so a quiet period at either end silently shrinks the window and
     * two charts of the same range can end up with different axes.
     */
    range = input<DateRange | null>(null);

    // color assignment follows each series' fixed index in metricsData(), never the
    // sorted/displayed legend order, so toggling the sort never repaints a series' color
    protected legendItems = computed<ChartLegendItem[]>(() => {
        const colors = this.themeService.isDark() ? CHART_COLORS_DARK : CHART_COLORS_LIGHT;
        const deselected = this.deselected();
        const items = this.metricsData().map((m, i) => ({
            name: m.name,
            color: colors[i % colors.length],
            selected: !deselected.has(m.name),
            total: m.values.reduce((sum: number, v) => sum + (v ?? 0), 0),
        }));
        if (this.legendSort() === 'name') {
            items.sort((a, b) => a.name.localeCompare(b.name));
        } else {
            items.sort((a, b) => b.total - a.total);
        }
        return items;
    });

    protected toggleLegend(click: LegendClick) {
        this.deselected.set(
            applyLegendClick(
                this.deselected(),
                this.metricsData().map(m => m.name),
                click
            )
        );
    }

    options = computed<EChartsCoreOption>(() => {
        /* we create a dataset looking like this:
        [
            ...
            ['2025-03-21T14:00:00Z', 55, 45, 46, 76],
            ['2025-03-21T15:00:00Z', 44, 65, 86, 76],
            ['2025-03-21T16:00:00Z', 23, 75, 43, 76],
            ...
        ]
        with dimensions:
            [timestamp, metricName-1, metricName-2, ..., metricName-n)
        */
        const allTimesSorted = this.extractAllTimestamps();
        const datasetSource: Array<Array<string | Maybe<number> | null>> = [];
        // The union of timestamps only contains buckets that at least one series reported. When
        // every series is silent the bucket is missing outright, so there is no null for the chart
        // to break on and a time axis just joins the points either side - a dead period reads as a
        // steady line. Insert one all-null row at the first missing bucket to break it.
        const bucketMs = this.detectBucketSize(allTimesSorted);
        const seriesCount = this.metricsData().length;
        let previousMs: number | null = null;

        allTimesSorted.forEach(time => {
            const currentMs = new Date(time).getTime();
            if (
                previousMs !== null &&
                bucketMs !== null &&
                currentMs - previousMs > bucketMs * 1.5
            ) {
                datasetSource.push([
                    new Date(previousMs + bucketMs).toISOString(),
                    ...new Array<null>(seriesCount).fill(null),
                ]);
            }
            previousMs = currentMs;

            const timeSeries: Array<string | number | null> = [time];
            let sum = 0;
            this.metricsData().forEach(metricHistory => {
                const index = metricHistory.times.indexOf(time);
                // `!= null` rather than a truthiness test: a real measurement of 0 is data, and
                // treating it as absent turned every zero into a hole in the series.
                const value = index > -1 ? metricHistory.values[index] : null;
                if (value != null) {
                    timeSeries.push(value);
                    if (this.normalized()) {
                        sum += value;
                    }
                } else {
                    timeSeries.push(null);
                }
            });
            if (this.normalized()) {
                this.normalize(timeSeries, sum);
            }
            datasetSource.push(timeSeries);
        });

        const deselected = this.deselected();
        const normalized = this.normalized();
        const range = this.range();
        const format = normalized ? formatPercent : formatCompact;

        return {
            tooltip: {
                trigger: 'axis',
                confine: true,
                appendTo: 'body',
                valueFormatter: (value: unknown) =>
                    typeof value === 'number' ? format(value) : '—',
            },
            // ECharts defaults the grid to 10% inset on each side, which leaves the plot area
            // noticeably narrower than its container. containLabel already reserves whatever the
            // axis labels need, so the explicit margins can be small.
            grid: {
                left: 8,
                right: 16,
                // room for the y-axis name, which ECharts draws above the axis and will
                // otherwise clip against the top of the canvas
                top: 32,
                bottom: 8,
                containLabel: true,
            },
            xAxis: {
                type: 'time',
                // pinned to the requested range, not the data extent - see the `range` input
                min: range?.from?.getTime(),
                max: range?.to?.getTime(),
            },
            yAxis: {
                type: 'value',
                axisLine: {
                    show: true,
                },
                max: normalized ? 100 : undefined,
                name: normalized ? '%' : 'bytes',
                axisLabel: {
                    formatter: (value: number) => format(value),
                },
            },
            dataset: {
                source: datasetSource,
                dimensions: ['timestamp', ...this.metricsData().map(m => m.name)],
            },
            series: this.metricsData().map(metricHistory => {
                if (this.type() === 'bar') {
                    return {
                        name: metricHistory.name,
                        type: 'bar',
                        showSymbol: false,
                        stack: '_',
                        encode: {
                            y: metricHistory.name, // https://github.com/apache/echarts/issues/14312
                        },
                    };
                }
                // type === line
                return {
                    name: metricHistory.name,
                    type: 'line',
                    areaStyle: {},
                    connectNulls: this.connectNulls(),
                    emphasis: {
                        focus: 'series',
                    },
                    showSymbol: false,
                    stack: '_',
                    encode: {
                        y: metricHistory.name, // https://github.com/apache/echarts/issues/14312
                    },
                };
            }),
            legend: {
                show: false,
                selected: Object.fromEntries(
                    this.metricsData().map(m => [m.name, !deselected.has(m.name)])
                ),
            },
        };
    });

    exportToCsv() {
        const csvLines: string[] = [];
        this.metricsData().forEach(history => {
            for (let i = 0; i < history.times.length; i++) {
                const time = history.times[i];
                const value = history.values[i];
                csvLines.push(`${time},${history.name},${value}`);
            }
        });
        csvLines.sort();
        const withHeader = ['time,name,value', ...csvLines];
        const blob = new Blob([withHeader.join('\n')], { type: 'text/csv' });
        const name = (this.panelData()?.title ?? '') + new Date().toISOString();
        saveAs(blob, name + '.csv');
    }

    private normalize(timeSeries: Array<string | number | null>, total: number) {
        // start loop with 1, first element is the timestamp
        for (let i = 1; i < timeSeries.length; i++) {
            const value = timeSeries[i];
            if (typeof value === 'number') {
                timeSeries[i] = (value / total) * 100;
            }
        }
    }

    /**
     * Median spacing between reported buckets, i.e. the sampling interval. The median rather than
     * the minimum so one short delta cannot shrink it, and rather than the mean so the gaps being
     * looked for do not inflate it.
     */
    private detectBucketSize(times: string[]): number | null {
        if (times.length < 2) {
            return null;
        }
        const deltas: number[] = [];
        for (let i = 1; i < times.length; i++) {
            deltas.push(new Date(times[i]).getTime() - new Date(times[i - 1]).getTime());
        }
        deltas.sort((a, b) => a - b);
        const median = deltas[Math.floor(deltas.length / 2)];
        return median > 0 ? median : null;
    }

    private extractAllTimestamps() {
        const allTimes: Set<string> = new Set();
        this.metricsData().forEach(metricHistory => {
            metricHistory.times.forEach((time: Maybe<Scalars['DateTime']['output']>) =>
                allTimes.add(time as string)
            );
        });
        const allTimesSorted: Array<string> = [...allTimes].sort(); // we can sort iso 8601 lexicographically
        return allTimesSorted;
    }

    onChartInit($event: EChartsType) {
        this.chartInit.emit($event);
    }
}
