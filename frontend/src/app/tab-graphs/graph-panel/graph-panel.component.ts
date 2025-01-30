import {Component, input} from '@angular/core';
import * as echarts from 'echarts/core';
import {EChartsCoreOption} from 'echarts/core';
import {BarChart, LineChart} from 'echarts/charts';
import {GridComponent} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
import {NgxEchartsDirective, provideEchartsCore} from 'ngx-echarts';
import {MetricHistory} from '../../../generated/graphql/types';

echarts.use([LineChart, BarChart, GridComponent, CanvasRenderer]);

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

    options: EChartsCoreOption = {
        xAxis: {
            type: 'category',
            data: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
        },
        yAxis: {
            type: 'value',
        },
        series: [
            {
                data: [820, 932, 901, 934, 1290, 1330, 1320],
                type: 'line',
            },
        ],
    };

}
