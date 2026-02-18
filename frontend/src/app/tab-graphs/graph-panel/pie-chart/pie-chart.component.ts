import { Component, computed, input, output } from '@angular/core';
import * as echarts from 'echarts/core';
import { EChartsCoreOption, EChartsType } from 'echarts/core';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import { saveAs } from 'file-saver-es';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MetricHistory } from '../../../../generated/graphql/sdk';

@Component({
    selector: 'app-pie-chart',
    imports: [NgxEchartsDirective, MatButton, MatIcon],
    templateUrl: './pie-chart.component.html',
    styleUrls: ['./pie-chart.component.scss'],
    providers: [provideEchartsCore({ echarts })],
})
export class PieChartComponent {
    chartInit = output<EChartsType>();

    metricsData = input.required<MetricHistory[]>();

    pieChartDataSet = computed(() => {
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
        return pieChartDataSet;
    });

    piechartOptions = computed<EChartsCoreOption>(() => {
        return {
            tooltip: {
                trigger: 'item',
            },
            dataset: {
                source: this.pieChartDataSet(),
            },
            series: [
                {
                    type: 'pie',
                    label: {
                        formatter: '{b} ({d}%)',
                    },
                },
            ],
        };
    });

    exportToCsv() {
        const csvLines: Array<string> = [];
        this.pieChartDataSet().forEach(pieSlice => {
            csvLines.push(pieSlice.join(','));
        });
        csvLines.sort();
        const withHeader = ['name, usage', ...csvLines];
        const blob = new Blob([withHeader.join('\n')], { type: 'text/csv' });
        const name = `report_${new Date().toISOString()}`;
        saveAs(blob, name + '.csv');
    }

    onChartInit($event: EChartsType) {
        this.chartInit.emit($event);
    }
}
