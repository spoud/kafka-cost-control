import {AfterViewInit, Component, OnInit, ViewChild} from '@angular/core';
import {GetPricingRulesGQL} from '../../../generated/graphql/sdk';
import {PricingRuleEntity} from '../../../generated/graphql/types';
import {MatSort, Sort} from '@angular/material/sort';
import {MatTableDataSource} from '@angular/material/table';
import {LiveAnnouncer} from '@angular/cdk/a11y';

@Component({
  selector: 'app-pricing-rules-list',
  templateUrl: './pricing-rules-list.component.html',
  styleUrl: './pricing-rules-list.component.scss'
})
export class PricingRulesListComponent implements OnInit, AfterViewInit {


  @ViewChild(MatSort) sort: MatSort | null = null;

  dataSource = new MatTableDataSource<PricingRuleEntity>([]);

  public displayedColumns: string[] = ['creationTime', 'metricName', 'baseCost', 'costFactor', 'costFactorGb'];

  constructor(private _pricingRules: GetPricingRulesGQL, private _liveAnnouncer: LiveAnnouncer) {

  }

  ngOnInit(): void {
    this._pricingRules.fetch().subscribe(value => this.dataSource.data = value.data.pricingRules);
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


  computeCostFactorGb(element: PricingRuleEntity): number | undefined {
    if (element.metricName.endsWith("bytes")) {
      return Math.round(element.costFactor * 1024 * 1024 * 1024*100000)/100000;
    }
    return undefined;
  }
}
