import { Component, computed, inject, output, resource, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom, map } from 'rxjs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIcon } from '@angular/material/icon';
import { MatButton } from '@angular/material/button';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatOption, MatSelect } from '@angular/material/select';
import { UnassignedEntitiesGQL } from '../../../generated/graphql/sdk';
import { EntityType } from '../../../generated/graphql/types';
import { IntlDatePipe } from '../../common/intl-date.pipe';
import { readRaw, writeRaw } from '../../common/persisted-state';
import { GraphFilterService } from '../../tab-graphs/graph-filter/graph-filter.service';

export interface UnassignedEntity {
    entityType: EntityType;
    name: string;
    metrics: string[];
    cost: number;
    lastSeen: string;
}

const LOOKBACK_DAYS = 7;
const KEY_STORAGE = 'kcc_unassigned_context_key';

/** A regex that matches exactly this name, for a rule pre-filled from the list. */
export function exactRegex(name: string): string {
    return `^(${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})$`;
}

/** "without any context" or "without cost-unit": what the list is about. */
export function unassignedLabel(contextKey: string | null): string {
    return contextKey ? `without ${contextKey}` : 'without any context';
}

/**
 * Topics and principals whose latest data no context rule assigned (or, for a chosen key, that
 * lack it), so their costs land in "<other>". Shown so a new service account or a topic outside
 * the naming convention gets noticed. Which keys matter differs per installation, so the key is a
 * choice, remembered per browser; by default anything with no context at all is listed.
 */
@Component({
    selector: 'app-unassigned-entities',
    imports: [
        MatExpansionModule,
        MatIcon,
        MatButton,
        MatFormField,
        MatLabel,
        MatSelect,
        MatOption,
        DecimalPipe,
        IntlDatePipe,
    ],
    templateUrl: './unassigned-entities.component.html',
    styleUrl: './unassigned-entities.component.scss',
})
export class UnassignedEntitiesComponent {
    private gql = inject(UnassignedEntitiesGQL);
    protected contextKeys = inject(GraphFilterService).contextKeys;

    /** The user wants a rule for this entity. */
    addRule = output<UnassignedEntity>();

    protected lookbackDays = LOOKBACK_DAYS;
    /** null: no context at all. */
    protected contextKey = signal<string | null>(readRaw(KEY_STORAGE) || null);
    protected label = computed(() => unassignedLabel(this.contextKey()));

    protected entities = resource({
        params: () => ({ contextKey: this.contextKey() }),
        loader: ({ params }) =>
            firstValueFrom(
                this.gql
                    .fetch({
                        variables: {
                            request: {
                                from: new Date(Date.now() - LOOKBACK_DAYS * 24 * 3600 * 1000),
                                contextKey: params.contextKey,
                            },
                        },
                        fetchPolicy: 'network-only',
                    })
                    .pipe(
                        map(res =>
                            (res.data?.unassignedEntities ?? []).map((e): UnassignedEntity => ({
                                entityType: e.entityType,
                                name: e.name,
                                metrics: e.metrics,
                                cost: e.cost,
                                lastSeen: String(e.lastSeen),
                            }))
                        )
                    )
            ),
    });

    reload(): void {
        this.entities.reload();
    }

    protected chooseKey(key: string | null): void {
        this.contextKey.set(key);
        writeRaw(KEY_STORAGE, key ?? '');
    }

    protected isTopic(entity: UnassignedEntity): boolean {
        return entity.entityType === EntityType.Topic;
    }

    protected shortMetric(metric: string): string {
        return metric.replace(/^confluent_kafka_server_/, '');
    }
}
