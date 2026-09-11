import { Component } from '@angular/core';
import { MatProgressBar } from '@angular/material/progress-bar';

@Component({
    selector: 'app-loading-indicator',
    imports: [MatProgressBar],
    templateUrl: './loading-indicator.component.html',
})
export class LoadingIndicatorComponent {}
