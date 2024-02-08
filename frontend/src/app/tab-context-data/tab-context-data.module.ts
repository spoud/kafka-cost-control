import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {ContextDataListComponent} from './context-data-list/context-data-list.component';
import {MaterialModule} from '../common/material.module';
import {RouterModule} from '@angular/router';
import {routes} from './tab-context-data.route';


@NgModule({
    declarations: [
        ContextDataListComponent
    ],
    imports: [
        CommonModule,
        RouterModule.forChild(routes),

        MaterialModule,
    ]
})
export class TabContextDataModule {
}
