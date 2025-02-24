import {Directive, effect, Signal, TemplateRef, ViewContainerRef} from '@angular/core';
import {BasicAuthServiceService} from './basic-auth-service.service';

@Directive({
    selector: '[appLoggedIn]'
})
export class LoggedInDirective<T = unknown> {

    constructor(
        private templateRef: TemplateRef<T>,
        private viewContainerRef: ViewContainerRef,
        private authService: BasicAuthServiceService,
    ) {
        const authenticated = this.authService.authenticated();
        effect(() => {
            this.updateView(authenticated);
        });
    }

    private updateView(authenticated: Signal<boolean>) {
        if (authenticated()) {
            this.viewContainerRef.createEmbeddedView(this.templateRef);
        } else {
            this.viewContainerRef.clear();
        }
    }
}
