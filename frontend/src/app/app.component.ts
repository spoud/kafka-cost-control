import {Component} from '@angular/core';
import {Router} from '@angular/router';

interface Link {
  path: string;
  label: string;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
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
