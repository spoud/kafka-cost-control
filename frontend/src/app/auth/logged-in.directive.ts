import { Directive, effect, TemplateRef, ViewContainerRef, inject } from '@angular/core';
import {BasicAuthServiceService} from './basic-auth-service.service';

@Directive({
    selector: '[appLoggedIn]'
})
export class LoggedInDirective<T = unknown> {
    private templateRef = inject<TemplateRef<T>>(TemplateRef);
    private viewContainerRef = inject(ViewContainerRef);
    private authService = inject(BasicAuthServiceService);


    constructor() {
        const authenticated = this.authService.authenticated();
        effect(() => {
            this.updateView(authenticated());
        });
    }

    private updateView(authenticated: boolean) {
        if (authenticated) {
            this.viewContainerRef.createEmbeddedView(this.templateRef);
        } else {
            this.viewContainerRef.clear();
        }
    }
}
