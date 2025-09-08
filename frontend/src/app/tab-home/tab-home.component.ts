import { Component } from '@angular/core';
import { MatGridList, MatGridTile } from '@angular/material/grid-list';
import { MatIcon } from '@angular/material/icon';
import { homeLinks } from '../app.routes';
import { RouterLink } from '@angular/router';

@Component({
    selector: 'app-tab-home',
    imports: [MatGridList, MatGridTile, RouterLink, MatIcon],
    templateUrl: './tab-home.component.html',
    styleUrl: './tab-home.component.scss',
})
export class TabHomeComponent {
    protected readonly homeLinks = homeLinks;
}
