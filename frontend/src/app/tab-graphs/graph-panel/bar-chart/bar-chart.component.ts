import { Component, computed, inject, input, output, signal } from '@angular/core';
import { MatCheckbox } from '@angular/material/checkbox';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { EChartsCoreOption, EChartsType } from 'echarts/core';
import { saveAs } from 'file-saver-es';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { Panel } from '../../../tab-reporting/panel.type';
import { Maybe, MetricHistory, Scalars } from '../../../../generated/graphql/types';
import { ThemeService } from '../../../services/theme.service';
import { CHART_COLORS_DARK, CHART_COLORS_LIGHT } from '../../../services/chart-theme';
import {
    ChartLegendComponent,
    ChartLegendItem,
    LegendSort,
} from '../chart-legend/chart-legend.component';

export type BarOrLine = 'bar' | 'line';

@Component({
    selector: 'app-bar-chart',
    imports: [MatCheckbox, NgxEchartsDirective, MatButton, MatIcon, ChartLegendComponent],
    templateUrl: './bar-chart.component.html',
    styleUrls: ['./bar-chart.component.scss'],
    providers: [provideEchartsCore({ echarts })],
})
export class BarChartComponent {
    protected readonly themeService = inject(ThemeService);

    chartInit = output<EChartsType>();

    normalized = signal(false);

    legendSort = signal<LegendSort>('value');

    deselected = signal<ReadonlySet<string>>(new Set());

    metricsData = input.required<MetricHistory[]>();

    panelData = input<Panel>();

    type = input.required<BarOrLine>();

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

    protected toggleLegend(name: string) {
        const next = new Set(this.deselected());
        if (next.has(name)) {
            next.delete(name);
        } else {
            next.add(name);
        }
        this.deselected.set(next);
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

        allTimesSorted.forEach(time => {
            const timeSeries: Array<string | number | null> = [time];
            let sum = 0;
            this.metricsData().forEach(metricHistory => {
                const index = metricHistory.times.indexOf(time);
                if (index > -1 && metricHistory.values[index]) {
                    timeSeries.push(metricHistory.values[index]);
                    if (this.normalized()) {
                        sum += metricHistory.values[index]!;
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

        return {
            tooltip: {
                trigger: 'axis',
                confine: true,
                appendTo: 'body',
            },
            grid: {
                containLabel: true,
            },
            xAxis: {
                type: 'time',
            },
            yAxis: {
                type: 'value',
                axisLine: {
                    show: true,
                },
                max: this.normalized() ? 100 : undefined,
                name: this.normalized() ? '%' : 'bytes',
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
