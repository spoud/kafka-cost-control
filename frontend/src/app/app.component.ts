import {Component} from '@angular/core';
import {Router, RouterLink, RouterLinkActive, RouterOutlet} from '@angular/router';
import {CommonModule} from '@angular/common';
import {MatTabNav, MatTabNavPanel, MatTabsModule} from '@angular/material/tabs';
import {MatToolbar, MatToolbarModule} from '@angular/material/toolbar';

interface Link {
    path: string;
    label: string;
}

@Component({
    selector: 'app-root',
    standalone: true,
    templateUrl: './app.component.html',
    styleUrl: './app.component.scss',
    imports: [
        CommonModule,


        RouterLink,
        RouterLinkActive,
        RouterOutlet,

        MatTabsModule,
        MatToolbar
    ],
})
export class AppComponent {
    navLinks: Link[] = [
        {path: '/context-data', label: 'Context Data'},
        {path: '/pricing-rules', label: 'Pricing Rules'},
        {path: '/others', label: 'Others'},
    ]
    activeLink: Link = this.navLinks[0];

    constructor(private router: Router) {

    }

    ngOnInit(): void {
        this.router.events.subscribe((res) => {
            this.activeLink = this.navLinks.find(tab => tab.path === '.' + this.router.url) || this.activeLink;
        });
    }
}
