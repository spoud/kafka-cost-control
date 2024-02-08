import {NgModule} from '@angular/core';
import {GraphQLModule} from './graphql.module';
import {HttpClientModule} from '@angular/common/http';
import {AppComponent} from './app.component';
import {BrowserModule} from '@angular/platform-browser';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {MatTabsModule} from '@angular/material/tabs';
import {MatToolbarModule} from '@angular/material/toolbar';
import {routes} from './app.routes';
import {RouterModule} from '@angular/router';
import {MAT_DATE_LOCALE} from "@angular/material/core";


@NgModule({
    imports: [
        BrowserModule,
        BrowserAnimationsModule,
        HttpClientModule,
        RouterModule.forRoot(routes),

        MatTabsModule,
        MatToolbarModule,
        GraphQLModule,
    ],
    declarations: [
        AppComponent,
    ],
    providers: [
        {provide: MAT_DATE_LOCALE, useValue: 'fr-CH'}
    ],
    bootstrap: [AppComponent]
})
export class AppModule {
}
