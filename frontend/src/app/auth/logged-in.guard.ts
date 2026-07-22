import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { BasicAuthServiceService } from './basic-auth-service.service';

export const loggedInGuard: CanActivateFn = (_route, state) => {
    const authService = inject(BasicAuthServiceService);
    const router = inject(Router);

    if (authService.authenticated()()) {
        return true;
    }

    return router.createUrlTree(['/unauthorized'], {
        queryParams: { returnUrl: state.url },
    });
};
