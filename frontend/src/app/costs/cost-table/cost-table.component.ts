import { AfterViewInit, Component, computed, effect, input, ViewChild } from '@angular/core';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { DataTableComponent } from '../../common/data-table/data-table.component';
import { METRIC_LABELS } from '../sankey/sankey.component';

/** One group's cost for one metric, in dollars. */
export interface CostRow {
    metric: string;
    context: string[];
    total: number;
    /** The part of `total` estimated from the pricing rules. */
    estimated: number;
    /** Share of the metric's total. */
    percentage: number;
}

@Component({
    selector: 'app-cost-table',
    imports: [
        MatTableModule,
        MatPaginatorModule,
        MatSortModule,
        PercentPipe,
        DecimalPipe,
        MatButtonModule,
        MatIcon,
        DataTableComponent,
    ],
    templateUrl: './cost-table.component.html',
    styleUrl: './cost-table.component.scss',
})
export class CostTableComponent implements AfterViewInit {
    rows = input.required<CostRow[]>();
    groupBy = input.required<string[]>();

    @ViewChild(MatPaginator) paginator: MatPaginator | undefined;
    @ViewChild(MatSort) sort: MatSort | undefined;

    protected readonly metricLabel = (metric: string) => METRIC_LABELS[metric] ?? metric;
    protected readonly dataSource = new MatTableDataSource<CostRow>([]);
    protected readonly hasEstimates = computed(() => this.rows().some(row => row.estimated > 0));
    protected readonly displayedColumns = computed(() => [
        'metric',
        ...this.groupBy(),
        'total',
        ...(this.hasEstimates() ? ['estimated'] : []),
        'percentage',
    ]);

    constructor() {
        effect(() => {
            this.dataSource.data = this.rows();
        });
        this.dataSource.sortingDataAccessor = (row, column) => {
            const index = this.groupBy().indexOf(column);
            if (index >= 0) {
                return row.context[index] ?? '';
            }
            const value = row[column as keyof CostRow];
            return typeof value === 'number' || typeof value === 'string' ? value : '';
        };
    }

    ngAfterViewInit() {
        this.dataSource.paginator = this.paginator ?? null;
        this.dataSource.sort = this.sort ?? null;
    }

    downloadCsv() {
        const keys = this.groupBy();
        const headers = ['Metric', ...keys, 'Cost ($)', 'Estimated ($)', 'Percentage'];
        const rows = this.rows().map(row => [
            this.metricLabel(row.metric),
            ...keys.map((_, i) => row.context.at(i) ?? ''),
            row.total,
            row.estimated,
            row.percentage,
        ]);

        const csv = [headers, ...rows]
            .map(row => row.map(cell => this.escapeCsv(cell)).join(','))
            .join('\n');

        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = 'cost-distribution.csv';
        link.click();
        URL.revokeObjectURL(url);
    }

    private escapeCsv(value: unknown): string {
        const str = value == null ? '' : String(value);
        if (/[",\n]/.test(str)) {
            return `"${str.replace(/"/g, '""')}"`;
        }
        return str;
    }
}
