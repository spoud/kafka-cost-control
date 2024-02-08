import {Component, input} from '@angular/core';
import {Entry_String_String} from '../../../generated/graphql/types';

@Component({
  selector: 'app-key-value-list',
  standalone: true,
  imports: [],
  templateUrl: './key-value-list.component.html',
  styleUrl: './key-value-list.component.scss'
})
export class KeyValueListComponent {

    entries = input.required<Entry_String_String[]>();

}
