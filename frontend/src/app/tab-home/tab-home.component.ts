import { Component, computed, inject, Signal } from '@angular/core';
import { MatGridList, MatGridTile } from '@angular/material/grid-list';
import { MatIcon } from '@angular/material/icon';
import { HomeLink, homeLinks, homeLinksLoggedIn } from '../app.routes';
import { RouterLink } from '@angular/router';
import { BasicAuthServiceService } from '../auth/basic-auth-service.service';

@Component({
    selector: 'app-tab-home',
    imports: [MatGridList, MatGridTile, RouterLink, MatIcon],
    templateUrl: './tab-home.component.html',
    styleUrl: './tab-home.component.scss',
})
export class TabHomeComponent {
    private _authService = inject(BasicAuthServiceService);
    private isAuthenticated = this._authService.authenticated();

    protected readonly links: Signal<HomeLink[]> = computed(() =>
        this.isAuthenticated() ? [...homeLinks, ...homeLinksLoggedIn] : homeLinks
    );
}
