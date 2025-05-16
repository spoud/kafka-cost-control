import {Component, inject, signal} from '@angular/core';
import {GraphFilterComponent} from './graph-filter/graph-filter.component';
import {GraphPanelComponent} from './graph-panel/graph-panel.component';
import {GraphFilterService} from './graph-filter/graph-filter.service';


export interface GraphFilter {
    from: Date;
    to?: Date;
    metricName?: string;
    groupByContext: string[];
}

@Component({
    selector: 'app-tab-graphs',
    imports: [
        GraphFilterComponent,
        GraphPanelComponent,
    ],
    templateUrl: './tab-graphs.component.html',
    styleUrl: './tab-graphs.component.scss'
})
export class TabGraphsComponent {

    graphFilterService = inject(GraphFilterService);

    filter = signal<GraphFilter | undefined>(undefined);

    historyData = this.graphFilterService.historyResource(this.filter);

}
