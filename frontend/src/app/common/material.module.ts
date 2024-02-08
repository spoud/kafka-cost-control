import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatTableModule} from '@angular/material/table';
import {MatSortModule} from '@angular/material/sort';
import {MapDisplayComponent} from './map-display/map-display.component';
import {MatFormFieldModule} from "@angular/material/form-field";
import {MatDatepickerModule} from "@angular/material/datepicker";
import {MatInputModule} from "@angular/material/input";
import {provideNativeDateAdapter} from "@angular/material/core";
import {MatButtonModule} from "@angular/material/button";
import {MatSnackBarModule} from "@angular/material/snack-bar";

@NgModule({
    declarations: [],
    imports: [
        CommonModule,

        MatTableModule,
        MatSortModule,

        MapDisplayComponent,
    ],
    exports: [
        MatTableModule,
        MatSortModule,

        MatFormFieldModule,
        MatDatepickerModule,
        MatInputModule,
        MatButtonModule,
        MatSnackBarModule,

        MapDisplayComponent,
    ],
    providers: [provideNativeDateAdapter()],
})
export class MaterialModule {
}
