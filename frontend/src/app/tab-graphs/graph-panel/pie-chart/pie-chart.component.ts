import {Component, computed, input, output} from '@angular/core';
import {MetricHistory} from '../../../../generated/graphql/types';
import * as echarts from 'echarts/core';
import {EChartsCoreOption, EChartsType} from 'echarts/core';
import {NgxEchartsDirective, provideEchartsCore} from 'ngx-echarts';

@Component({
    selector: 'app-pie-chart',
    imports: [
        NgxEchartsDirective
    ],
    templateUrl: './pie-chart.component.html',
    providers: [
        provideEchartsCore({echarts}),
    ]
})
export class PieChartComponent {

    chartInit = output<EChartsType>();

    metricsData = input.required<MetricHistory[]>();

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

    onChartInit($event: EChartsType) {
        this.chartInit.emit($event);
    }
}
