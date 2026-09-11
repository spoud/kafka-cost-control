import {
    AfterViewInit,
    Component,
    computed,
    OnInit,
    signal,
    ViewChild,
    inject,
} from '@angular/core';
import { GetPricingRulesGQL } from '../../../generated/graphql/sdk';
import { PricingRuleEntity } from '../../../generated/graphql/types';
import { MatSort, MatSortModule, Sort } from '@angular/material/sort';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BytesToGbPipe } from './cost-factor.pipe';
import { PageHeaderComponent } from '../../common/page-header/page-header.component';
import { DataTableComponent } from '../../common/data-table/data-table.component';

@Component({
    selector: 'app-pricing-rules-list',
    templateUrl: './pricing-rules-list.component.html',
    styleUrl: './pricing-rules-list.component.scss',
    imports: [
        MatTableModule,
        MatSortModule,
        MatPaginatorModule,
        BytesToGbPipe,
        PageHeaderComponent,
        DataTableComponent,
    ],
})
export class PricingRulesListComponent implements OnInit, AfterViewInit {
    private _pricingRules = inject(GetPricingRulesGQL);
    private _liveAnnouncer = inject(LiveAnnouncer);
    private _snackbar = inject(MatSnackBar);

    @ViewChild(MatSort) sort: MatSort | null = null;
    @ViewChild(MatPaginator) paginator: MatPaginator | null = null;

    dataSource = new MatTableDataSource<PricingRuleEntity>([]);

    loading = signal(true);
    error = signal<string | null>(null);
    empty = computed(() => !this.loading() && !this.error() && this.dataSource.data.length === 0);

    public displayedColumns: string[] = [
        'creationTime',
        'metricName',
        'baseCost',
        'costFactor',
        'costFactorGb',
    ];

    ngOnInit(): void {
        this._pricingRules.fetch().subscribe({
            next: value => {
                this.loading.set(false);
                if (value.error) {
                    this.error.set(value.error.message);
                    this._snackbar.open(
                        'Could not load pricing rules. ' + value.error.message,
                        'close'
                    );
                } else if (value.data) {
                    this.dataSource.data = value.data.pricingRules;
                }
            },
            error: err => {
                this.loading.set(false);
                this.error.set(err.message);
            },
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
