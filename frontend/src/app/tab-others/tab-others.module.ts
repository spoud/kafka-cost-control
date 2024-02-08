import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {OthersComponent} from './others/others.component';
import {RouterModule} from '@angular/router';

@NgModule({
    imports: [
        CommonModule,
        RouterModule.forChild([
            {
                path: '',
                component: OthersComponent,
            },
        ]),
    ]
})
export class TabOthersModule {
}
