import {
    AfterViewInit,
    Component,
    computed,
    OnInit,
    signal,
    TemplateRef,
    ViewChild,
    inject,
} from '@angular/core';
import { DeletePricingRuleGQL, GetPricingRulesGQL } from '../../../generated/graphql/sdk';
import { PricingRuleEntity } from '../../../generated/graphql/types';
import { MatSort, MatSortModule, Sort } from '@angular/material/sort';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatSnackBar } from '@angular/material/snack-bar';
import { PricePipe } from '../price';
import { PageHeaderComponent } from '../../common/page-header/page-header.component';
import { DataTableComponent } from '../../common/data-table/data-table.component';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIcon } from '@angular/material/icon';
import { IntlDatePipe } from '../../common/intl-date.pipe';
import { UnpricedMetricsService } from '../../common/unpriced-metrics';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { LoggedInDirective } from '../../auth/logged-in.directive';
import { ConfirmDialogComponent } from '../../common/confirm-dialog/confirm-dialog.component';
import {
    PricingRuleSaveComponent,
    PricingRuleSaveData,
} from '../pricing-rule-save/pricing-rule-save.component';

/** How long the aggregator's rule store takes to reflect a change, with some margin. */
const RULE_STORE_CATCH_UP_MS = 2000;

@Component({
    selector: 'app-pricing-rules-list',
    templateUrl: './pricing-rules-list.component.html',
    styleUrl: './pricing-rules-list.component.scss',
    imports: [
        MatTableModule,
        MatSortModule,
        MatPaginatorModule,
        PricePipe,
        PageHeaderComponent,
        DataTableComponent,
        MatExpansionModule,
        MatIcon,
        IntlDatePipe,
        MatButton,
        MatIconButton,
        LoggedInDirective,
    ],
})
export class PricingRulesListComponent implements OnInit, AfterViewInit {
    private _pricingRules = inject(GetPricingRulesGQL);
    private _deletePricingRule = inject(DeletePricingRuleGQL);
    private _dialog = inject(MatDialog);
    private _liveAnnouncer = inject(LiveAnnouncer);
    private _snackbar = inject(MatSnackBar);
    unpriced = inject(UnpricedMetricsService);

    @ViewChild(MatSort) sort: MatSort | null = null;
    @ViewChild(MatPaginator) paginator: MatPaginator | null = null;
    @ViewChild('deleteConfirmContent') deleteConfirmContent!: TemplateRef<unknown>;

    dataSource = new MatTableDataSource<PricingRuleEntity>([]);

    loading = signal(true);
    error = signal<string | null>(null);
    empty = computed(() => !this.loading() && !this.error() && this.dataSource.data.length === 0);

    public displayedColumns: string[] = [
        'metricName',
        'price',
        'baseCost',
        'creationTime',
        'buttons',
    ];

    ngOnInit(): void {
        this.loadPricingRules();
    }

    private loadPricingRules() {
        this.unpriced.reload();
        this.loading.set(true);
        this._pricingRules.fetch({ fetchPolicy: 'network-only' }).subscribe({
            next: value => {
                this.loading.set(false);
                if (value.error) {
                    this.error.set(value.error.message);
                    this._snackbar.open(
                        'Could not load pricing rules. ' + value.error.message,
                        'close'
                    );
                } else if (value.data) {
                    this.error.set(null);
                    this.dataSource.data = value.data.pricingRules;
                }
            },
            error: err => {
                this.loading.set(false);
                this.error.set(err.message);
            },
        });
    }

    /**
     * Rule changes go through Kafka, so the aggregator's rule store shows them a moment after the
     * mutation returns. Show the change now and reload once the store has caught up.
     */
    private showUntilReloaded(change: (rules: PricingRuleEntity[]) => PricingRuleEntity[]) {
        this.dataSource.data = change(this.dataSource.data);
        setTimeout(() => this.loadPricingRules(), RULE_STORE_CATCH_UP_MS);
    }

    /** Adds a rule, optionally for a given metric, or edits `rule`. */
    openSaveDialog(rule?: PricingRuleEntity, metricName?: string) {
        const data: PricingRuleSaveData = {
            rule,
            metricName,
            metricNames: this.unpriced.collected().map(m => m.metricName),
            pricedMetricNames: this.dataSource.data.map(r => r.metricName),
        };
        this._dialog
            .open(PricingRuleSaveComponent, { data, width: '560px' })
            .afterClosed()
            .subscribe((saved?: PricingRuleEntity) => {
                if (saved) {
                    this.showUntilReloaded(rules => [
                        ...rules.filter(r => r.metricName !== saved.metricName),
                        saved,
                    ]);
                }
            });
    }

    delete(rule: PricingRuleEntity) {
        this._dialog
            .open(ConfirmDialogComponent, {
                data: {
                    title: 'Delete pricing rule',
                    contentTemplate: this.deleteConfirmContent,
                    templateContext: { $implicit: rule },
                    confirmLabel: 'Yes, delete',
                    cancelLabel: 'No, cancel',
                    destructive: true,
                },
            })
            .afterClosed()
            .subscribe(confirmed => {
                if (!confirmed) {
                    return;
                }
                this._deletePricingRule
                    .mutate({ variables: { request: { metricName: rule.metricName } } })
                    .subscribe({
                        next: () => {
                            this._snackbar.open('Pricing rule deleted', 'close', {
                                politeness: 'polite',
                                duration: 2000,
                            });
                            this.showUntilReloaded(rules =>
                                rules.filter(r => r.metricName !== rule.metricName)
                            );
                        },
                        error: err =>
                            this._snackbar.open(`Deleting failed: ${err.message}`, 'close'),
                    });
            });
    }

    ngAfterViewInit() {
        this.dataSource.sort = this.sort;
        this.dataSource.paginator = this.paginator;
    }

    /** Announce the change in sort state for assistive technology. */
    announceSortChange(sortState: Sort) {
        // This example uses English messages. If your application supports
        // multiple language, you would internationalize these strings.
        // Furthermore, you can customize the message to add additional
        // details about the values being sorted.
        if (sortState.direction) {
            this._liveAnnouncer.announce(`Sorted ${sortState.direction}ending`);
        } else {
            this._liveAnnouncer.announce('Sorting cleared');
        }
    }
}
