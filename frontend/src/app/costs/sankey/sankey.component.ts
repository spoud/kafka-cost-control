import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { CostOverviewQuery } from '../../../generated/graphql/sdk';
import { CostOverviewRequestInput } from '../../../generated/graphql/types';
import { NgxEchartsDirective } from 'ngx-echarts';
import { EChartsType } from 'echarts/core';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import * as echarts from 'echarts/core';
import { EChartsCoreOption } from 'echarts/core';
import { SankeyChart } from 'echarts/charts';
import { ThemeService } from '../../services/theme.service';

echarts.use([SankeyChart]);

/**
 * Most entries to draw per metric before the remainder is collapsed into one node. Chosen to keep
 * the fixed-height canvas readable rather than from any property of the data.
 */
const MAX_ENTRIES_PER_METRIC = 25;

@Component({
    selector: 'app-sankey',
    imports: [NgxEchartsDirective, MatIconButton, MatIcon, MatTooltip],
    templateUrl: './sankey.component.html',
    styleUrl: './sankey.component.scss',
})
export class SankeyComponent {
    protected readonly themeService = inject(ThemeService);

    private chart: EChartsType | null = null;
    private readonly destroyRef = inject(DestroyRef);

    /** Bumped to force a fresh option object, which drops ECharts' accumulated roam transform. */
    private resetCount = signal(0);

    protected readonly modifierKey = navigator.platform.startsWith('Mac') ? '\u2318' : 'Ctrl';

    protected onChartInit(chart: EChartsType): void {
        this.chart = chart;

        // The diagram is a tall element in a scrolling page and `roam` binds the wheel, so
        // scrolling past it zoomed instead of scrolling. Swallow the event in the capture phase -
        // before ECharts' own handler on the canvas inside - unless a modifier is held. No
        // preventDefault, so the page scrolls normally.
        //
        // Done natively rather than with an Angular binding: `(wheel.capture)` is not an event
        // modifier Angular understands outside key events, it would bind an event of that literal
        // name and never fire.
        const container = chart.getDom();
        const onWheel = (event: WheelEvent) => {
            if (!event.metaKey && !event.ctrlKey) {
                event.stopPropagation();
            }
        };
        container.addEventListener('wheel', onWheel, { capture: true });
        this.destroyRef.onDestroy(() =>
            container.removeEventListener('wheel', onWheel, { capture: true })
        );
    }

    protected resetView(): void {
        // No public action resets sankey roam, so re-apply the option non-mergefully: ECharts
        // rebuilds the view and the pan/zoom transform goes with it.
        this.resetCount.update(n => n + 1);
        this.chart?.setOption(this.sankeyOptions(), { notMerge: true });
    }

    inputData = input.required<CostOverviewQuery | undefined>();
    lastRequest = input.required<CostOverviewRequestInput | undefined>();

    sankeyOptions = computed<EChartsCoreOption>(() => {
        this.resetCount(); // a reset produces a new option object; see resetView
        const storage = (this.lastRequest()?.kafkaStorageCents ?? 0) / 100; // dollar amount...
        const kafkaIn = (this.lastRequest()?.kafkaNetworkReadCents ?? 0) / 100;
        const kafkaOut = (this.lastRequest()?.kafkaNetworkWriteCents ?? 0) / 100;
        // above together with some other things added, e.g. base costs
        const total = (this.lastRequest()?.totalCents ?? 0) / 100;
        const other = total - storage - kafkaOut - kafkaIn;

        const dataSet = new Set<string>();
        // node id -> short label shown in the diagram; nodes not listed here (metrics, total, other)
        // just display their own name. The full breadcrumb id is still what shows up in tooltips.
        const shortLabels = new Map<string, string>();
        // accumulate link values by "source\u0000target" so the same (parent, child) pair reached from
        // multiple breakdown rows - e.g. two different apps under the same customer - merges into one link
        // instead of drawing duplicate/conflicting edges between the same two nodes.
        const linkValues = new Map<string, { source: string; target: string; value: number }>();
        const addLink = (source: string, target: string, value: number) => {
            const key = `${source}\u0000${target}`;
            const existing = linkValues.get(key);
            if (existing) {
                existing.value += value;
            } else {
                linkValues.set(key, { source, target, value });
            }
        };

        dataSet.add('total');
        dataSet.add('confluent_kafka_server_retained_bytes');
        dataSet.add('confluent_kafka_server_request_bytes');
        dataSet.add('confluent_kafka_server_response_bytes');
        dataSet.add('other');

        addLink('total', 'confluent_kafka_server_retained_bytes', storage);
        addLink('total', 'confluent_kafka_server_request_bytes', kafkaIn);
        addLink('total', 'confluent_kafka_server_response_bytes', kafkaOut);
        addLink('total', 'other', other);

        const groupByKeys = this.lastRequest()?.contextKeysToGroupBy ?? [];
        this.inputData()?.costOverview.metricToDistributionMapList?.forEach(entry => {
            const metric = entry?.metric;
            if (!metric) {
                return;
            }
            dataSet.add(metric);
            const entries = entry?.nameToPriceList?.filter(x => !!x) ?? [];

            // Bound the diagram. Every entry contributes one node per group-by level, so a
            // high-cardinality key such as `topic` would otherwise emit thousands of nodes into a
            // fixed-height canvas. Keep the biggest contributors and roll the rest into a single
            // sibling so the total still adds up.
            const ranked = [...entries].sort((a, b) => (b.price ?? 0) - (a.price ?? 0));
            const shown = ranked.slice(0, MAX_ENTRIES_PER_METRIC);
            const remainder = ranked.slice(MAX_ENTRIES_PER_METRIC);
            if (remainder.length > 0) {
                const restTotal = remainder.reduce((sum, e) => sum + (e.price ?? 0), 0) / 100;
                const restLabel = `${remainder.length} smaller`;
                const restPath = `${metric} › ${restLabel}`;
                dataSet.add(restPath);
                shortLabels.set(restPath, restLabel);
                addLink(metric, restPath, restTotal);
            }

            shown.forEach(nameToPrice => {
                // Keep positions: values are paired with groupByKeys by index below, so
                // dropping an empty value would shift every later one onto the wrong key and
                // merge the mislabelled node with a real one. The backend uses "<other>" for a
                // missing context value, so match it rather than inventing a second spelling.
                const values = (nameToPrice.contextValues ?? []).map(v => v || '<other>');
                const price = (nameToPrice.price ?? 0) / 100;

                // walk the ordered group-by values, turning each prefix into its own hierarchy
                // level: metric -> customer -> customer+app -> ..., instead of one flat leaf node
                // per (customer, app) combination.
                let parent = metric;
                let path = '';
                for (let i = 0; i < values.length; i++) {
                    const key = groupByKeys[i] ?? `level${i + 1}`;
                    const segmentLabel = `${key}=${values[i]}`;
                    path = path ? `${path} › ${segmentLabel}` : segmentLabel;
                    dataSet.add(path);
                    shortLabels.set(path, segmentLabel);
                    addLink(parent, path, price);
                    parent = path;
                }
            });
        });

        const data = Array.from(dataSet, name => ({ name }));
        const links = Array.from(linkValues.values());
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
                // pan freely by dragging; zoom is wheel-driven and gated in onWheelCapture
                roam: true,
                label: {
                    formatter: (params: { name?: string }) =>
                        shortLabels.get(params.name ?? '') ?? params.name ?? '',
                },
                data: data,
                links: links,
            },
        };
    });
}
