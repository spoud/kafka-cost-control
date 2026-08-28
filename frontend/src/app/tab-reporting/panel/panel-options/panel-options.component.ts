import { Component, computed, inject, input, Signal } from '@angular/core';
import { MatIconButton, MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Panel, PANEL_TYPE_LABELS, PanelType } from '../../panel.type';
import { GraphFilterComponent } from '../../../tab-graphs/graph-filter/graph-filter.component';
import { GraphFilter } from '../../../tab-graphs/tab-graphs.component';
import { PanelStore } from '../../store/panel.store';

@Component({
    selector: 'app-panel-options',
    imports: [
        MatIconButton,
        MatButton,
        MatIcon,
        GraphFilterComponent,
        MatLabel,
        MatFormField,
        MatInput,
        MatSelectModule,
    ],
    templateUrl: './panel-options.component.html',
    styleUrl: './panel-options.component.scss',
})
export class PanelOptionsComponent {
    panelStore = inject(PanelStore);

    panelData = input.required<Panel>();

    protected readonly panelTypes = PANEL_TYPE_LABELS;

    graphFilter: Signal<GraphFilter> = computed(
        () => {
            return {
                from: this.panelData().from,
                to: this.panelData().to,
                metricName: this.panelData().metricName,
                groupByContext: this.panelData().groupByContext,
            };
        },
        { equal: (a, b) => JSON.stringify(a) === JSON.stringify(b) }
    );

    updateFilter($event: GraphFilter) {
        this.panelStore.updatePanel(this.panelData().id, { ...$event });
    }

    // Every field goes through the store rather than [(ngModel)] on panelData(). Binding straight
    // to the input object mutated the stored entity in place, which never notified the signal, so
    // a renamed panel looked right until the next reload and then came back with the old title.
    updateTitle(title: string) {
        this.panelStore.updatePanel(this.panelData().id, { title });
    }

    updateDescription(description: string) {
        this.panelStore.updatePanel(this.panelData().id, { description });
    }

    updateType(type: PanelType) {
        this.panelStore.updatePanel(this.panelData().id, { type });
    }

    close() {
        this.panelStore.stopEditing();
    }

    remove() {
        this.panelStore.removePanel(this.panelData().id);
    }
}
