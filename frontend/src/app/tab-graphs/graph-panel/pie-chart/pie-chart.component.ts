import { Component, computed, inject, input, output, signal } from '@angular/core';
import * as echarts from 'echarts/core';
import { EChartsCoreOption, EChartsType } from 'echarts/core';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import { saveAs } from 'file-saver-es';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MetricHistory } from '../../../../generated/graphql/types';
import { ThemeService } from '../../../services/theme.service';
import { CHART_COLORS_DARK, CHART_COLORS_LIGHT } from '../../../services/chart-theme';
import {
    ChartLegendComponent,
    ChartLegendItem,
    LegendSort,
} from '../chart-legend/chart-legend.component';

@Component({
    selector: 'app-pie-chart',
    imports: [NgxEchartsDirective, MatButton, MatIcon, ChartLegendComponent],
    templateUrl: './pie-chart.component.html',
    styleUrls: ['./pie-chart.component.scss'],
    providers: [provideEchartsCore({ echarts })],
})
export class PieChartComponent {
    protected readonly themeService = inject(ThemeService);

    chartInit = output<EChartsType>();

    legendSort = signal<LegendSort>('value');

    deselected = signal<ReadonlySet<string>>(new Set());

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

    // color assignment follows each slice's fixed index in pieChartDataSet(), never the
    // sorted/displayed legend order, so toggling the sort never repaints a slice's color
    protected legendItems = computed<ChartLegendItem[]>(() => {
        const colors = this.themeService.isDark() ? CHART_COLORS_DARK : CHART_COLORS_LIGHT;
        const deselected = this.deselected();
        const items = this.pieChartDataSet().map(([name, total], i) => ({
            name: name as string,
            color: colors[i % colors.length],
            selected: !deselected.has(name as string),
            total: total as number,
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

    piechartOptions = computed<EChartsCoreOption>(() => {
        const deselected = this.deselected();

        return {
            tooltip: {
                trigger: 'item',
            },
            dataset: {
                source: this.pieChartDataSet(),
            },
            legend: {
                show: false,
                selected: Object.fromEntries(
                    this.pieChartDataSet().map(([name]) => [name, !deselected.has(name as string)])
                ),
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
