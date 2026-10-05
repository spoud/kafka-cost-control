import { Injectable, computed, inject, resource } from '@angular/core';
import { firstValueFrom, map } from 'rxjs';
import { AssistantStatusGQL } from '../../generated/graphql/sdk';
import { AuthService } from '../auth/auth.service';

/**
 * Whether this deployment has the AI assistant configured.
 *
 * The assistant is optional and off by default, so the navigation entry is hidden unless the
 * backend says a question would actually be answered — otherwise a user opens a chat box and only
 * discovers it is switched off after typing something.
 */
@Injectable({ providedIn: 'root' })
export class AssistantStatusService {
    private readonly gql = inject(AssistantStatusGQL);
    private readonly auth = inject(AuthService);

    // asked once signed in: before that, every request is refused
    private readonly statusResource = resource({
        params: () => (this.auth.signedIn() ? true : undefined),
        loader: () =>
            firstValueFrom(this.gql.fetch().pipe(map(response => response.data?.assistantStatus))),
    });

    /**
     * False while loading, so the nav entry appears once rather than flickering in and out on
     * every page load.
     *
     * Guarded with hasValue(): reading value() on a failed resource throws, and this feeds the
     * app shell's nav. An unreachable backend must hide one optional entry, not take down the
     * whole sidebar.
     */
    readonly available = computed(() =>
        this.statusResource.hasValue() ? (this.statusResource.value()?.available ?? false) : false
    );

    /** Why it is unavailable, shown on the page itself for whoever has to fix the config. */
    readonly reason = computed(() =>
        this.statusResource.hasValue() ? (this.statusResource.value()?.reason ?? null) : null
    );

    /** True until the backend has answered — lets the page avoid claiming anything prematurely. */
    readonly loading = computed(() => this.statusResource.isLoading());
}
