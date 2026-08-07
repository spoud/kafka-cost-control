import { Component, inject } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIcon } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { PageHeaderComponent } from '../common/page-header/page-header.component';
import { ThemeMode, ThemeService } from '../services/theme.service';

@Component({
    selector: 'app-settings',
    imports: [PageHeaderComponent, MatButtonToggleModule, MatIcon, MatCardModule],
    templateUrl: './settings.component.html',
    styleUrl: './settings.component.scss',
})
export class SettingsComponent {
    private _theme = inject(ThemeService);

    themeMode = this._theme.themeMode;

    setThemeMode(mode: ThemeMode): void {
        this._theme.setThemeMode(mode);
    }
}
