import { Component, computed, inject, input } from '@angular/core';
import { Panel, TYPE_TO_COMPONENT_MAPPING } from '../panel.type';
import { NgComponentOutlet } from '@angular/common';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatCard, MatCardContent } from '@angular/material/card';
import { PanelOptionsComponent } from './panel-options/panel-options.component';
import { MetaDataPipe } from './meta-data.pipe';
import { PanelStore } from '../store/panel.store';

@Component({
    selector: 'app-panel',
    imports: [
        NgComponentOutlet,
        MatIconButton,
        MatIcon,
        MatCard,
        MatCardContent,
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

    openOptions(): void {
        this.panelStore.startEditing(this.panelData().id);
    }
}
