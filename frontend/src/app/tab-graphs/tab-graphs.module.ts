import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {RouterModule} from '@angular/router';
import {TabGraphsComponent} from './tab-graphs.component';


@NgModule({
    imports: [
        CommonModule,
        RouterModule.forChild([
            {
                path: '',
                component: TabGraphsComponent,
            }]),

    ]
})
export class TabGraphsModule {
}
