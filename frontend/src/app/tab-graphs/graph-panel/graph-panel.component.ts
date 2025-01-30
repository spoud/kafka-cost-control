import {Component, computed, input} from '@angular/core';
import * as echarts from 'echarts/core';
import {EChartsCoreOption} from 'echarts/core';
import {BarChart, LineChart} from 'echarts/charts';
import {GridComponent, LegendComponent} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
import {NgxEchartsDirective, provideEchartsCore} from 'ngx-echarts';
import {MetricHistory} from '../../../generated/graphql/types';
import {SeriesOption} from 'echarts';
import {LineDataItemOption} from 'echarts/types/src/chart/line/LineSeries';

echarts.use([LineChart, BarChart, GridComponent, CanvasRenderer, LegendComponent]);

@Component({
    selector: 'app-graph-panel',
    imports: [NgxEchartsDirective],
    templateUrl: './graph-panel.component.html',
    styleUrl: './graph-panel.component.scss',
    providers: [
        provideEchartsCore({echarts}),
    ]
})
export class GraphPanelComponent {

    metricsData = input<MetricHistory[]>([]);

    options = computed<EChartsCoreOption>(() => {
        const series: SeriesOption[] = [];
        const legendDataObjects: any[] = []; // Todo DataItem (see https://github.com/apache/echarts/pull/15241)

        this.metricsData().forEach(metricData => {
            const name = metricData.name;
            series.push(this.drawOneLIne(metricData, name));
            legendDataObjects.push({
                name,
                icon: 'none',
            });
        });


        return {
            xAxis: {
                type: 'category',
            },
            yAxis: {
                // type: 'value',
            },
            series: series,
            legend: {
                orient: 'vertical',
                right: 10,
                top: 'center'
            }
        };
    });

    protected drawOneLIne(history: MetricHistory, name: string): SeriesOption {
        const data: LineDataItemOption[] = this.extractDataFromChannel(history);

        return {
            data,
            name,
            type: 'line',
            symbol: 'none',
            // itemStyle: {
            //     color,
            // },
            // lineStyle: {
            //     color,
            // },
        };
    }

    protected extractDataFromChannel(history: MetricHistory): LineDataItemOption[] {
        // No 100% sure of the return type...
        const data: LineDataItemOption[] = [];

        for (let i = 0; i < history.times.length; i++) {
            const time = history.times[i];
            const value = history.values[i];
            data.push({
                value: [time, value]
            });
        }


        return data;
    }


}
