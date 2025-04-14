import {Component, input} from '@angular/core';
import * as echarts from 'echarts/core';
import {BarChart, LineChart, PieChart} from 'echarts/charts';
import {
    DatasetComponent,
    DataZoomComponent,
    GridComponent,
    LegendComponent,
    TooltipComponent
} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
import {provideEchartsCore} from 'ngx-echarts';
import {MetricHistory} from '../../../generated/graphql/types';
import {MatDivider} from '@angular/material/divider';
import {BarChartComponent} from './bar-chart/bar-chart.component';
import {PieChartComponent} from './pie-chart/pie-chart.component';

echarts.use([LineChart, BarChart, GridComponent, CanvasRenderer, LegendComponent, PieChart, TooltipComponent, DatasetComponent, DataZoomComponent]);
@Component({
    selector: 'app-graph-panel',
    imports: [MatDivider, BarChartComponent, PieChartComponent],
    templateUrl: './graph-panel.component.html',
    providers: [
        provideEchartsCore({echarts}),
    ]
})
export class GraphPanelComponent {

    metricsData = input.required<MetricHistory[]>();

}
