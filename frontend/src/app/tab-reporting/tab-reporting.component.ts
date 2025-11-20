import { Component, inject } from '@angular/core';
import { PanelComponent } from './panel/panel.component';
import { MatButton, MatFabButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { IntlDatePipe } from '../common/intl-date.pipe';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import JSPDF from 'jspdf';
import { PanelStore } from './store/panel.store';
import * as echarts from 'echarts/core';
import { EChartsType } from 'echarts/core';
import { ResizeOpts } from 'echarts/types/dist/shared';
import { provideEchartsCore } from 'ngx-echarts';

@Component({
    selector: 'app-tab-reporting',
    imports: [
        PanelComponent,
        MatIcon,
        MatFabButton,
        IntlDatePipe,
        MatMenu,
        MatMenuItem,
        MatMenuTrigger,
        MatButton,
    ],
    templateUrl: './tab-reporting.component.html',
    styleUrl: './tab-reporting.component.scss',
    providers: [provideEchartsCore({ echarts })],
})
export class TabReportingComponent {
    panelStore = inject(PanelStore);

    date = new Date();

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

        pdf.text('Report ' + this.date.toLocaleString(), 10, 10);
        for (let i = 0; i < imageUrls.length; i++) {
            const title = this.panelStore.entities()[i].title;
            const description = this.panelStore.entities()[i].description ?? '';
            const offset = (i % 2) * 150;
            pdf.setFontSize(14);
            pdf.text(title, pdf.internal.pageSize.width / 2, 15 + offset, { align: 'center' });
            pdf.setFontSize(10);
            pdf.text(description, pdf.internal.pageSize.width / 2, 22 + offset, {
                align: 'center',
            });
            pdf.addImage(imageUrls[i], 'png', 5, 28 + offset, 0, 0);
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
