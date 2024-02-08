import {Component, input} from '@angular/core';
import {Entry_String_String} from '../../../generated/graphql/types';

@Component({
    selector: 'app-map-display',
    standalone: true,
    imports: [],
    templateUrl: './map-display.component.html',
    styleUrl: './map-display.component.scss'
})
export class MapDisplayComponent {

    entries = input.required<Entry_String_String[]>();

}
