import { Component, inject } from '@angular/core';
import { PanelComponent } from './panel/panel.component';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { IntlDatePipe } from '../common/intl-date.pipe';
import { IntlDateService } from '../services/intl-date.service';
import { formatPanelMeta } from './panel/meta-data.pipe';
import JSPDF from 'jspdf';
import { PanelStore } from './store/panel.store';
import * as echarts from 'echarts/core';
import { EChartsType } from 'echarts/core';
import { ResizeOpts } from 'echarts/types/dist/shared';
import { provideEchartsCore } from 'ngx-echarts';
import { PageHeaderComponent } from '../common/page-header/page-header.component';

@Component({
    selector: 'app-tab-reporting',
    imports: [PanelComponent, MatIcon, IntlDatePipe, MatButton, PageHeaderComponent],
    templateUrl: './tab-reporting.component.html',
    styleUrl: './tab-reporting.component.scss',
    providers: [provideEchartsCore({ echarts })],
})
export class TabReportingComponent {
    panelStore = inject(PanelStore);
    private intlDateService = inject(IntlDateService);

    date = new Date();

    // A4 portrait is 210mm wide. The chart canvases are rendered at 800x400 for export, and
    // addImage with width 0 sizes from the image's own pixel dimensions - roughly 280mm - so the
    // right-hand side of every chart used to run off the page. Fit to the printable width instead.
    private static readonly PAGE_MARGIN_MM = 10;
    private static readonly CHART_ASPECT = 400 / 800;

    print() {
        const pdf = new JSPDF();
        const eChartsInstances = this.panelStore
            .entities()
            .map(panel => panel.eChartsInstance)
            .filter(p => !!p);
        let oldSizes: ResizeOpts[] = [];
        const imageUrls: string[] = [];
        try {
            oldSizes = this.resizeToA4(eChartsInstances);
            eChartsInstances.forEach(eChart => {
                const canvas = this.toCanvas(eChart);
                if (!canvas) {
                    return;
                }
                imageUrls.push(canvas?.toDataURL('image/png'));
            });
        } finally {
            // if anything breaks during image rendering we still reset HTML view to previous sizes
            this.resizeToPrevious(eChartsInstances, oldSizes);
        }

        const margin = TabReportingComponent.PAGE_MARGIN_MM;
        const pageWidth = pdf.internal.pageSize.width;
        const chartWidth = pageWidth - margin * 2;
        const chartHeight = chartWidth * TabReportingComponent.CHART_ASPECT;
        const centre = pageWidth / 2;

        pdf.text('Report ' + this.date.toLocaleString(), margin, 10);
        for (let i = 0; i < imageUrls.length; i++) {
            const panel = this.panelStore.entities()[i];
            const offset = (i % 2) * 150;
            pdf.setFontSize(14);
            pdf.text(panel.title, centre, 15 + offset, { align: 'center' });
            pdf.setFontSize(10);
            pdf.text(panel.description ?? '', centre, 22 + offset, { align: 'center' });
            // what the chart is actually of - metric, window and grouping. Without it a printed
            // report is a page of unlabelled shapes.
            pdf.setFontSize(8);
            pdf.text(formatPanelMeta(panel, this.intlDateService), centre, 28 + offset, {
                align: 'center',
                maxWidth: chartWidth,
            });
            pdf.addImage(imageUrls[i], 'png', margin, 32 + offset, chartWidth, chartHeight);
            if (i % 2 == 1 && i < imageUrls.length - 1) {
                pdf.addPage();
            }
        }
        pdf.save(`KafkaCCReport-${this.date.toISOString()}.pdf`);
    }

    private resizeToA4(eChartsInstances: EChartsType[]) {
        const oldSizes: ResizeOpts[] = [];
        eChartsInstances.forEach(eChart => {
            oldSizes.push({ width: eChart.getWidth(), height: eChart.getHeight() });
            eChart.resize({ width: 800, height: 400, silent: true });
        });
        return oldSizes;
    }

    private resizeToPrevious(eChartsInstances: EChartsType[], oldSizes: ResizeOpts[]) {
        for (let i = 0; i < eChartsInstances.length; i++) {
            eChartsInstances[i].resize(oldSizes[i]);
        }
    }

    toCanvas(image: EChartsType): HTMLCanvasElement | undefined {
        const canvas = document.createElement('canvas');
        canvas.width = image.getWidth();
        canvas.height = image.getHeight();

        const context = canvas.getContext('2d');
        if (context === null) {
            console.error('Cannot create 2D rendering Context');
            return;
        }
        context.drawImage(image.renderToCanvas(), 0, 0, image.getWidth(), image.getHeight());

        return canvas;
    }
}
