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

/** Vertical room to reserve per node in the busiest column, so labels are not stacked on top of
 * each other. The canvas grows with the data instead of collapsing the data to fit the canvas. */
const PIXELS_PER_NODE = 26;
const MIN_CHART_HEIGHT = 600;

/**
 * Distinct colour per node. The shared 8-colour chart palette repeats every 8 entries, which on a
 * tenant/application breakdown left most applications looking identical. Successive hues are a
 * golden angle apart, so neighbouring nodes are always far apart on the wheel.
 */
function nodeColor(index: number, isDark: boolean): string {
    const hue = (index * 137.508) % 360;
    // Comma-separated on purpose: zrender's colour parser splits on commas, so the modern
    // space-separated CSS form parses as a single component and every node renders black.
    return isDark ? `hsl(${hue}, 55%, 62%)` : `hsl(${hue}, 62%, 45%)`;
}

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

    /**
     * Zoom works on every platform - onWheelCapture accepts either metaKey or ctrlKey. Only the
     * hint text differs, and navigator.platform is deprecated, so prefer userAgentData.
     */
    protected readonly modifierKey = /mac|iphone|ipad/i.test(
        (navigator as { userAgentData?: { platform?: string } }).userAgentData?.platform ??
            navigator.userAgent
    )
        ? '\u2318'
        : 'Ctrl';

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

    /**
     * Nodes, links and the busiest column, built once. `sankeyOptions` and `chartHeight` both
     * derive from this: a computed may not write to a signal, so the height cannot simply be set
     * while assembling the option object.
     */
    private model = computed(() => {
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
        // node -> how many hops from `total`, so the busiest column can be measured
        const depths = new Map<string, number>([['total', 0]]);
        const addLink = (source: string, target: string, value: number) => {
            depths.set(target, (depths.get(source) ?? 0) + 1);
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

            // Everything is drawn - no "N smaller" roll-up. The canvas height is derived from the
            // node count below, so a high-cardinality group-by produces a tall diagram rather than
            // a truncated one.
            const shown = [...entries].sort((a, b) => (b.price ?? 0) - (a.price ?? 0));

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

        const isDark = this.themeService.isDark();
        const data = Array.from(dataSet, (name, index) => ({
            name,
            itemStyle: { color: nodeColor(index, isDark) },
        }));
        const links = Array.from(linkValues.values());

        const perDepth = new Map<number, number>();
        depths.forEach(depth => perDepth.set(depth, (perDepth.get(depth) ?? 0) + 1));
        const busiestColumn = Math.max(1, ...perDepth.values());

        return { data, links, shortLabels, busiestColumn };
    });

    /** Tall enough that every node in the busiest column has room for its label. */
    chartHeight = computed(() =>
        Math.max(MIN_CHART_HEIGHT, this.model().busiestColumn * PIXELS_PER_NODE)
    );

    sankeyOptions = computed<EChartsCoreOption>(() => {
        const { data, links, shortLabels } = this.model();
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
                // breathing room between nodes in a column so low-value labels stop colliding
                nodeGap: 14,
                lineStyle: { color: 'gradient', opacity: 0.45 },
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
