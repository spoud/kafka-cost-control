import {AfterViewInit, Component, Inject, OnInit, ViewChild} from '@angular/core';
import {GetContextDatasGQL} from '../../../generated/graphql/sdk';
import {ContextDataEntity} from '../../../generated/graphql/types';
import {LiveAnnouncer} from '@angular/cdk/a11y';
import {MatSort, MatSortModule, Sort} from '@angular/material/sort';
import {MatTableDataSource, MatTableModule} from '@angular/material/table';
import {MatSnackBar} from '@angular/material/snack-bar';
import {KeyValueListComponent} from '../../common/key-value-list/key-value-list.component';
import {MatFabButton} from '@angular/material/button';
import {MatIcon} from '@angular/material/icon';
import {ContextDataCreateComponent} from '../context-data-create/context-data-create.component';
import {MatDialog, MatDialogRef} from '@angular/material/dialog';
import {MatDivider} from '@angular/material/divider';
import {LoggedInDirective} from '../../auth/logged-in.directive';
import {BROWSER_LOCALE} from '../../app.config';
import {IntlDatePipe} from '../../common/intl-date.pipe';

@Component({
    selector: 'app-context-data-list',
    templateUrl: './context-data-list.component.html',
    styleUrl: './context-data-list.component.scss',
    imports: [
        MatTableModule,
        MatSortModule,
        KeyValueListComponent,
        MatFabButton,
        MatIcon,
        MatDivider,
        LoggedInDirective,
        IntlDatePipe
    ]
})
export class ContextDataListComponent implements OnInit, AfterViewInit {

    @ViewChild(MatSort) sort: MatSort | null = null;

    // public contextDataList: ContextDataEntity[] = [];
    dataSource = new MatTableDataSource<ContextDataEntity>([]);

    public displayedColumns: string[] = ['creationTime', 'validFrom', 'validUntil', 'entityType', 'regex', 'context'];

    constructor(
        private contextDataService: GetContextDatasGQL,
        private _liveAnnouncer: LiveAnnouncer,
        private _snackBar: MatSnackBar,
        private dialog: MatDialog,
        @Inject(BROWSER_LOCALE) public browserLocale: string,
    ) {
    }

    ngOnInit(): void {
        this.loadContextData();
    }

    private loadContextData() {
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

    openCreateDialog() {
        const dialogRef: MatDialogRef<ContextDataCreateComponent> = this.dialog.open(ContextDataCreateComponent);
        dialogRef.afterClosed().subscribe(result => {
            if (result === 'successfully-saved') {
                this.loadContextData();
            }
        });
    }
}
