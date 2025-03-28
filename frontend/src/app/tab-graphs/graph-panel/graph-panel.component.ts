import {Component, computed, input, signal} from '@angular/core';
import * as echarts from 'echarts/core';
import {EChartsCoreOption} from 'echarts/core';
import {BarChart, LineChart, PieChart} from 'echarts/charts';
import {
    DatasetComponent,
    DataZoomComponent,
    GridComponent,
    LegendComponent,
    TooltipComponent
} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
import {NgxEchartsDirective, provideEchartsCore} from 'ngx-echarts';
import {Maybe, MetricHistory, Scalars} from '../../../generated/graphql/types';
import {MatCheckbox} from '@angular/material/checkbox';
import {MatDivider} from '@angular/material/divider';

echarts.use([LineChart, BarChart, GridComponent, CanvasRenderer, LegendComponent, PieChart, TooltipComponent, DatasetComponent, DataZoomComponent]);

@Component({
    selector: 'app-graph-panel',
    imports: [NgxEchartsDirective, MatCheckbox, MatDivider],
    templateUrl: './graph-panel.component.html',
    styleUrl: './graph-panel.component.scss',
    providers: [
        provideEchartsCore({echarts}),
    ]
})
export class GraphPanelComponent {

    normalized = signal(false);

    metricsData = input<MetricHistory[]>([]);

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
                        sum += metricHistory.values[index];
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

        return {
            tooltip: {
                trigger: 'axis',
            },
            dataZoom: [
                {
                    type: 'inside',
                    start: 0,
                    end: 100,
                },
                {
                    start: 0,
                    end: 100
                }
            ],
            xAxis: {
                type: 'time',
            },
            yAxis: {
                type: 'value',
                axisLine: {
                    show: true,
                },
                max: this.normalized() ? 100 : undefined,
                name: this.normalized() ? "%" : "bytes",
            },
            dataset: {
                source: datasetSource,
                dimensions: ['timestamp', ...this.metricsData().map(m => m.name)],
            },
            series: this.metricsData().map(metricHistory => {
                return {
                    name: metricHistory.name,
                    type: 'bar',
                    stack: '_',
                    encode: {
                        y: metricHistory.name // https://github.com/apache/echarts/issues/14312
                    }
                }
            }),
            legend: {}
        };
    });

    private normalize(timeSeries: Array<string | number | null>, total: number) {
        // start loop with 1, first element is the timestamp
        for (let i = 1; i < timeSeries.length; i++) {
            const value = timeSeries[i];
            if (typeof value === 'number') {
                timeSeries[i] = value / total * 100;
            }
        }
    }

    piechartOptions = computed<EChartsCoreOption>(() => {
        const pieChartDataSet: Array<Array<string | number>> = [];
        this.metricsData().forEach(metricHistory => {
            let total = 0;
            metricHistory.values.forEach(value => {
                if (value) {
                    total += value;
                }
            });
            pieChartDataSet.push([metricHistory.name, total]);
        });

        return {
            tooltip: {
                trigger: 'item',
            },
            dataset: {
                source: pieChartDataSet,
            },
            series: [
                {
                    type: 'pie',
                    label: {
                        formatter: '{b} ({d}%)'
                    }
                },
            ]
        };
    });

    private extractAllTimestamps() {
        const allTimes: Set<string> = new Set();
        this.metricsData().forEach(metricHistory => {
            metricHistory.times.forEach((time: Maybe<Scalars['DateTime']['output']>) => allTimes.add(time));
        });
        const allTimesSorted: Array<string> = [...allTimes].sort(); // we can sort iso 8601 lexicographically
        return allTimesSorted;
    }
}
