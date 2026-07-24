import { Component, computed, input, ViewChild, AfterViewInit } from '@angular/core';
import { CalculateTableQuery } from '../../../generated/graphql/sdk';
import { CostOverviewRequestInput } from '../../../generated/graphql/types';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import { PercentPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';

@Component({
    selector: 'app-cost-table',
    imports: [
        MatTableModule,
        MatPaginatorModule,
        MatSortModule,
        PercentPipe,
        MatButtonModule,
        MatIcon,
    ],
    templateUrl: './cost-table.component.html',
    styleUrl: './cost-table.component.scss',
})
export class CostTableComponent implements AfterViewInit {
    inputData = input.required<CalculateTableQuery>();
    lastRequest = input.required<CostOverviewRequestInput | undefined>();

    @ViewChild(MatPaginator) paginator: MatPaginator | undefined;
    @ViewChild(MatSort) sort: MatSort | undefined;

    fixedColStart = ['initialMetricName'];
    fixedColEnd = ['total', 'percentage'];

    displayedColumns = computed(() => {
        if (!this.lastRequest()) {
            return [];
        }
        if (this.lastRequest()?.contextKeysToGroupBy?.length === 0) {
            return [...this.fixedColStart, ...this.fixedColEnd];
        }
        return [
            ...this.fixedColStart,
            ...this.lastRequest()!.contextKeysToGroupBy!,
            ...this.fixedColEnd,
        ];
    });

    dataSource = computed(() => {
        return new MatTableDataSource(this.inputData().calculateTable.entries!);
    });

    ngAfterViewInit() {
        this.dataSource = computed(() => {
            const matTableDataSource = new MatTableDataSource(
                this.inputData().calculateTable.entries!
            );
            matTableDataSource.paginator = this.paginator;
            matTableDataSource.sort = this.sort;
            return matTableDataSource;
        });
    }

    downloadCsv() {
        const contextKeys = this.lastRequest()?.contextKeysToGroupBy ?? [];
        const headers = ['Metric', ...contextKeys, 'Total', 'Percentage'];
        const entries = this.inputData().calculateTable.entries ?? [];

        const rows = entries
            .filter(entries => !!entries)
            .map(entry => [
                entry.initialMetricName,
                ...contextKeys.map((_, i) => entry.context?.at(i) ?? ''),
                entry.total,
                entry.percentage,
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
