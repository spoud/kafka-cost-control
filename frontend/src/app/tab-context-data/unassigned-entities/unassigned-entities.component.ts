import { Component, inject, output, resource } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { firstValueFrom, map } from 'rxjs';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIcon } from '@angular/material/icon';
import { MatButton } from '@angular/material/button';
import { UnassignedEntitiesGQL } from '../../../generated/graphql/sdk';
import { EntityType } from '../../../generated/graphql/types';
import { IntlDatePipe } from '../../common/intl-date.pipe';

export interface UnassignedEntity {
    entityType: EntityType;
    name: string;
    metrics: string[];
    cost: number;
    lastSeen: string;
}

const LOOKBACK_DAYS = 7;

/** A regex that matches exactly this name, for a rule pre-filled from the list. */
export function exactRegex(name: string): string {
    return `^(${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})$`;
}

/**
 * Topics and principals whose latest data has no tenant: no context rule assigns them, so their
 * costs land in "<other>". Shown so a new service account or a topic outside the naming convention
 * gets noticed instead of disappearing into that bucket.
 */
@Component({
    selector: 'app-unassigned-entities',
    imports: [MatExpansionModule, MatIcon, MatButton, DecimalPipe, IntlDatePipe],
    templateUrl: './unassigned-entities.component.html',
    styleUrl: './unassigned-entities.component.scss',
})
export class UnassignedEntitiesComponent {
    private gql = inject(UnassignedEntitiesGQL);

    /** The user wants a rule for this entity. */
    addRule = output<UnassignedEntity>();

    protected lookbackDays = LOOKBACK_DAYS;
    protected entities = resource({
        loader: () =>
            firstValueFrom(
                this.gql
                    .fetch({
                        variables: {
                            request: {
                                from: new Date(Date.now() - LOOKBACK_DAYS * 24 * 3600 * 1000),
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

    protected isTopic(entity: UnassignedEntity): boolean {
        return entity.entityType === EntityType.Topic;
    }

    protected shortMetric(metric: string): string {
        return metric.replace(/^confluent_kafka_server_/, '');
    }
}
