import { Component, input } from '@angular/core';
import { MatIcon } from '@angular/material/icon';
import { LoadingIndicatorComponent } from '../loading-indicator/loading-indicator.component';
import { EmptyStateComponent } from '../empty-state/empty-state.component';

@Component({
    selector: 'app-data-table',
    imports: [MatIcon, LoadingIndicatorComponent, EmptyStateComponent],
    templateUrl: './data-table.component.html',
    styleUrl: './data-table.component.scss',
})
export class DataTableComponent {
    loading = input<boolean>(false);
    error = input<string | null>(null);
    empty = input<boolean>(false);
    emptyMessage = input<string>('No data to display.');
}
