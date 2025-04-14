import {Component, computed, inject, input, model, Signal} from '@angular/core';
import {MatIconButton} from '@angular/material/button';
import {MatIcon} from '@angular/material/icon';
import {Panel} from '../../panel.type';
import {GraphFilterComponent} from '../../../tab-graphs/graph-filter/graph-filter.component';
import {GraphFilter} from '../../../tab-graphs/tab-graphs.component';
import {PanelStore} from '../../store/panel.store';
import {MatInput} from '@angular/material/input';
import {MatFormField, MatLabel} from '@angular/material/form-field';
import {FormsModule} from '@angular/forms';

@Component({
    selector: 'app-panel-options',
    imports: [
        MatIconButton,
        MatIcon,
        GraphFilterComponent,
        MatLabel,
        MatFormField,
        MatInput,
        FormsModule
    ],
    templateUrl: './panel-options.component.html',
    styleUrl: './panel-options.component.scss'
})
export class PanelOptionsComponent {

    panelStore = inject(PanelStore);

    showOptions = model<boolean>(false);

    panelData = input.required<Panel>();

    graphFilter: Signal<GraphFilter> = computed(() => {
        return {
            from: this.panelData().from,
            to: this.panelData().to,
            metricName: this.panelData().metricName,
            groupByContext: this.panelData().groupByContext,
        }
    });

    updateFilter($event: GraphFilter) {
        console.log("filter updated...", $event);
        this.panelStore.updatePanel(this.panelData().id, {...$event});
    }
}
