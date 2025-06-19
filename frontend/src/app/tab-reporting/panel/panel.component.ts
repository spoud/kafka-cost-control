import {Component, computed, input, signal} from '@angular/core';
import {Panel, TYPE_TO_COMPONENT_MAPPING} from '../panel.type';
import {NgComponentOutlet} from '@angular/common';
import {MatIconButton} from '@angular/material/button';
import {MatIcon} from '@angular/material/icon';
import {PanelOptionsComponent} from './panel-options/panel-options.component';
import {MatDivider} from '@angular/material/divider';
import {MetaDataPipe} from './meta-data.pipe';

@Component({
    selector: 'app-panel',
    imports: [
        NgComponentOutlet,
        MatIconButton,
        MatIcon,
        PanelOptionsComponent,
        MatDivider,
        MetaDataPipe
    ],
    templateUrl: './panel.component.html',
    styleUrl: './panel.component.scss',
    host: {
        '[style.grid-area]': '"span " + (panelData().rows ?? 3) + "/ span " + (panelData().columns ?? 1)',
    }
})
export class PanelComponent {

    panelData = input.required<Panel>();

    component = computed(() => {
        return TYPE_TO_COMPONENT_MAPPING[this.panelData().type]
    });

    showOptions = signal(false);
}
