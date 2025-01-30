import {Component, signal} from '@angular/core';
import {GraphFilterComponent} from './graph-filter/graph-filter.component';
import {GraphPanelComponent} from './graph-panel/graph-panel.component';
import {JsonPipe} from '@angular/common';


export interface GraphFilter {
    metricName?: string;
    groupByContext: string[];
}

@Component({
    selector: 'app-tab-graphs',
    imports: [
        GraphFilterComponent,
        GraphPanelComponent,
        JsonPipe
    ],
    templateUrl: './tab-graphs.component.html',
    styleUrl: './tab-graphs.component.scss'
})
export class TabGraphsComponent {

    filter = signal<GraphFilter|undefined>(undefined);

}
