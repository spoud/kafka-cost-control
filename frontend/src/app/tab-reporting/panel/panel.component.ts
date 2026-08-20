import { Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { Panel, TYPE_TO_COMPONENT_MAPPING } from '../panel.type';
import { NgComponentOutlet } from '@angular/common';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatCard, MatCardContent } from '@angular/material/card';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { PanelOptionsComponent } from './panel-options/panel-options.component';
import { MetaDataPipe } from './meta-data.pipe';
import { PanelStore } from '../store/panel.store';
import { ChartActions, isChartActions } from '../../tab-graphs/graph-panel/chart-actions';

@Component({
    selector: 'app-panel',
    imports: [
        NgComponentOutlet,
        MatIconButton,
        MatIcon,
        MatCard,
        MatCardContent,
        MatMenu,
        MatMenuItem,
        MatMenuTrigger,
        PanelOptionsComponent,
        MetaDataPipe,
    ],
    templateUrl: './panel.component.html',
    styleUrl: './panel.component.scss',
})
export class PanelComponent {
    private panelStore = inject(PanelStore);

    panelData = input.required<Panel>();

    component = computed(() => TYPE_TO_COMPONENT_MAPPING[this.panelData().type]);

    /** Driven by the store so a newly added panel opens straight into its options. */
    showOptions = computed(() => this.panelStore.editingPanelId() === this.panelData().id);

    private outlet = viewChild(NgComponentOutlet);

    /**
     * The chart renders through ngComponentOutlet, so its own overflow menu would sit stacked
     * under this panel's. Pulling its actions up here keeps one control in the corner.
     * Read on menu open rather than as a computed: componentInstance is a plain getter, so it
     * would not notify a signal when the outlet swaps components on a chart-type change.
     */
    protected chart = signal<ChartActions | null>(null);

    protected syncChartActions(): void {
        const instance = this.outlet()?.componentInstance;
        this.chart.set(isChartActions(instance) ? instance : null);
    }

    protected toggleLegend(): void {
        const showLegend = this.chart()?.showLegend;
        showLegend?.set(!showLegend());
    }

    protected toggleConnectNulls(): void {
        const connectNulls = this.chart()?.connectNulls;
        connectNulls?.set(!connectNulls());
    }

    protected toggleNormalized(): void {
        const normalized = this.chart()?.normalized;
        normalized?.set(!normalized());
    }

    openOptions(): void {
        this.panelStore.startEditing(this.panelData().id);
    }

    remove(): void {
        this.panelStore.removePanel(this.panelData().id);
    }
}
