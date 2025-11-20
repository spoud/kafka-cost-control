import { AfterViewInit, Component, inject, OnInit, ViewChild } from '@angular/core';
import { DeleteContextDataGQL, GetContextDatasGQL } from '../../../generated/graphql/sdk';
import { ContextDataEntity } from '../../../generated/graphql/types';
import { LiveAnnouncer } from '@angular/cdk/a11y';
import { MatSort, MatSortModule, Sort } from '@angular/material/sort';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatSnackBar } from '@angular/material/snack-bar';
import { KeyValueListComponent } from '../../common/key-value-list/key-value-list.component';
import { MatButton, MatFabButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { ContextDataSaveComponent } from '../context-data-save/context-data-save.component';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { LoggedInDirective } from '../../auth/logged-in.directive';
import { IntlDatePipe } from '../../common/intl-date.pipe';
import { ContextDataTestComponent } from '../context-data-test/context-data-test.component';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';

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
        LoggedInDirective,
        IntlDatePipe,
        MatButton,
        MatIconButton,
    ],
})
export class ContextDataListComponent implements OnInit, AfterViewInit {
    private contextDataService = inject(GetContextDatasGQL);
    private deleteContextDataService = inject(DeleteContextDataGQL);
    private _liveAnnouncer = inject(LiveAnnouncer);
    private _snackBar = inject(MatSnackBar);
    private dialog = inject(MatDialog);

    @ViewChild(MatSort) sort: MatSort | null = null;

    // public contextDataList: ContextDataEntity[] = [];
    dataSource = new MatTableDataSource<ContextDataEntity>([]);

    public displayedColumns: string[] = [
        'creationTime',
        'validFrom',
        'validUntil',
        'entityType',
        'regex',
        'context',
        'buttons',
    ];

    ngOnInit(): void {
        this.loadContextData();
    }

    private loadContextData() {
        this.contextDataService.fetch().subscribe({
            next: value => (this.dataSource.data = value.data.contextData),
            error: err =>
                this._snackBar.open('Could not load context data. ' + err.message, 'close'),
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
        const dialogRef: MatDialogRef<ContextDataSaveComponent> =
            this.dialog.open(ContextDataSaveComponent);
        dialogRef.afterClosed().subscribe(result => {
            if (result === 'successfully-saved') {
                this.loadContextData();
            }
        });
    }

    edit(element: ContextDataEntity) {
        const dialogRef: MatDialogRef<ContextDataSaveComponent> = this.dialog.open(
            ContextDataSaveComponent,
            {
                data: { element },
            }
        );
        dialogRef.afterClosed().subscribe(result => {
            if (result === 'successfully-saved') {
                this.loadContextData();
            }
        });
    }

    delete(element: ContextDataEntity) {
        const confirmDialogRef = this.dialog.open(ConfirmDialogComponent, {
            data: { element },
        });
        confirmDialogRef.afterClosed().subscribe(confirmation => {
            if (confirmation) {
                this.deleteContextDataService.mutate({ request: { id: element.id! } }).subscribe({
                    next: _ => {
                        this.loadContextData();
                        this._snackBar.open(
                            `Successfully deleted context data with regex "${element.regex}".`
                        );
                    },
                    error: err => {
                        this._snackBar.open(`Error while deleting context data. Reason: ${err}`);
                    },
                });
            }
        });
    }

    openTestDialog() {
        this.dialog.open(ContextDataTestComponent);
    }
}
