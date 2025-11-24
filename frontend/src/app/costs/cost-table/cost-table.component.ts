import { Component, computed, input, ViewChild, AfterViewInit } from '@angular/core';
import { CalculateTableQuery, CalculateTopDownRequestInput } from '../../../generated/graphql/sdk';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatSort, MatSortModule } from '@angular/material/sort';
import {DecimalPipe, PercentPipe} from '@angular/common';

@Component({
    selector: 'app-cost-table',
    imports: [MatTableModule, MatPaginatorModule, MatSortModule, PercentPipe, DecimalPipe],
    templateUrl: './cost-table.component.html',
    styleUrl: './cost-table.component.scss',
})
export class CostTableComponent implements AfterViewInit {
    inputData = input.required<CalculateTableQuery>();
    lastRequest = input.required<CalculateTopDownRequestInput | undefined>();

    // @ts-ignore
    @ViewChild(MatPaginator) paginator: MatPaginator;
    // @ts-ignore
    @ViewChild(MatSort) sort: MatSort;

    fixedColStart = ['initialMetricName'];
    fixedColEnd = ['total', 'percentage'];

    displayedColumns = computed(() => {
        if (this.lastRequest()?.contextKeysToGroupBy?.length === 0) {
            return [...this.fixedColStart, ...this.fixedColEnd];
        }
        return [
            ...this.fixedColStart,
            ...this.lastRequest()?.contextKeysToGroupBy!,
            ...this.fixedColEnd,
        ];
    });

    dataSource = computed(() => {
        return new MatTableDataSource(this.inputData().calculateTable.entries);
    });

    ngAfterViewInit() {
        this.dataSource = computed(() => {
            const matTableDataSource = new MatTableDataSource(
                this.inputData().calculateTable.entries
            );
            matTableDataSource.paginator = this.paginator;
            matTableDataSource.sort = this.sort;
            return matTableDataSource;
        });
    }
}
