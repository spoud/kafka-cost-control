import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {OthersComponent} from './others/others.component';
import {RouterModule} from '@angular/router';
import {routes} from './tab-others.route';
import {MatButtonModule} from "@angular/material/button";
import {MaterialModule} from "../common/material.module";
import {FormsModule} from "@angular/forms";


@NgModule({
  declarations: [
    OthersComponent
  ],
  imports: [
    CommonModule,
    RouterModule.forChild(routes),

    MaterialModule,
    FormsModule
  ]
})
export class TabOthersModule {
}
