import { Component, computed, input } from '@angular/core';
import {
    CalculateTopDownQuery,
    CalculateTopDownRequestInput,
} from '../../../generated/graphql/sdk';
import { NgxEchartsDirective } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { EChartsCoreOption } from 'echarts/core';
import { SankeyChart } from 'echarts/charts';

echarts.use([SankeyChart]);

@Component({
    selector: 'app-sankey',
    imports: [NgxEchartsDirective],
    templateUrl: './sankey.component.html',
    styleUrl: './sankey.component.scss',
})
export class SankeyComponent {
    inputData = input.required<CalculateTopDownQuery | undefined>();
    lastRequest = input.required<CalculateTopDownRequestInput | undefined>();

    sankeyOptions = computed<EChartsCoreOption>(() => {
        console.log(this.inputData());
        console.log(this.lastRequest());
        // lets say inputs into this component are also from the form and it includes a (computed) total:
        const storage = (this.lastRequest()?.kafkaStorageCents ?? 0) / 100; // dollar amount...
        const kafkaIn = (this.lastRequest()?.kafkaNetworkReadCents ?? 0) / 100;
        const kafkaOut = (this.lastRequest()?.kafkaNetworkWriteCents ?? 0) / 100;
        const total = (this.lastRequest()?.totalCents ?? 0) / 100; // above together with some other things, e.g. base costs
        const other = total - storage - kafkaOut - kafkaIn;

        const dataSet = new Set<string | undefined>();
        const links = [];
        dataSet.add('total');
        dataSet.add('confluent_kafka_server_retained_bytes');
        dataSet.add('confluent_kafka_server_request_bytes');
        dataSet.add('confluent_kafka_server_response_bytes');
        dataSet.add('other');

        links.push(
            {
                source: 'total',
                target: 'confluent_kafka_server_retained_bytes',
                value: storage,
            },
            {
                source: 'total',
                target: 'confluent_kafka_server_request_bytes',
                value: kafkaIn,
            },
            {
                source: 'total',
                target: 'confluent_kafka_server_response_bytes',
                value: kafkaOut,
            },
            {
                source: 'total',
                target: 'other',
                value: other,
            }
        );
        this.inputData()?.calculateTopDown.metricToDistributionMapList?.forEach(entry => {
            const metric = entry?.metric;
            dataSet.add(metric);
            entry?.nameToPriceList?.forEach(nameToPrice => {
                if ((nameToPrice?.price ?? 9999) < 10) {
                    return;
                }
                dataSet.add(nameToPrice?.name);
                links.push({
                    source: metric,
                    target: nameToPrice?.name,
                    value: (nameToPrice?.price ?? 0) / 100, // todo - do in backend
                });
            });
        });
        const data: Array<{ name: string | undefined }> = [];
        dataSet.forEach(x => data.push({ name: x }));
        return {
            tooltip: {
                trigger: 'item',
                triggerOn: 'mousemove',
            },
            series: {
                type: 'sankey',
                emphasis: {
                    focus: 'trajectory',
                },
                layout: 'none',
                data: data,
                links: links,
            },
        };
    });
}
