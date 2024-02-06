import {AfterViewInit, Component, OnInit, ViewChild} from '@angular/core';
import {GetContextDatasGQL} from '../../../generated/graphql/sdk';
import {ContextDataEntity} from '../../../generated/graphql/types';
import {LiveAnnouncer} from '@angular/cdk/a11y';
import {MatSort, Sort} from '@angular/material/sort';
import {MatTableDataSource} from '@angular/material/table';
import {MatSnackBar} from '@angular/material/snack-bar';

@Component({
    selector: 'app-context-data-list',
    templateUrl: './context-data-list.component.html',
    styleUrl: './context-data-list.component.scss'
})
export class ContextDataListComponent implements OnInit, AfterViewInit {


    @ViewChild(MatSort) sort: MatSort | null = null;

    // public contextDataList: ContextDataEntity[] = [];
    dataSource = new MatTableDataSource<ContextDataEntity>([]);

    public displayedColumns: string[] = ['creationTime', 'validFrom', 'validUntil', 'entityType', 'regex', 'context'];

    constructor(private contextDataService: GetContextDatasGQL, private _liveAnnouncer: LiveAnnouncer, private _snackBar: MatSnackBar) {
    }

    ngOnInit(): void {
        this.contextDataService.fetch().subscribe({
            next: value => this.dataSource.data = value.data.contextData,
            error: err => this._snackBar.open("Could not load context data. " + err.message, "close")
        });
    }

    ngAfterViewInit() {
        this.dataSource.sort = this.sort;
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
