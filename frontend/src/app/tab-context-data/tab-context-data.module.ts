import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {RouterModule} from '@angular/router';
import {ContextDataListComponent} from './context-data-list/context-data-list.component';


@NgModule({
    imports: [
        CommonModule,
        RouterModule.forChild([
            {
                path: '',
                component: ContextDataListComponent,
            }]),

    ]
})
export class TabContextDataModule {
}
