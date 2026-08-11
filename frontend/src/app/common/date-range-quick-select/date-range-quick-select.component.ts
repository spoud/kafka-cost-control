import { Component, input, output } from '@angular/core';
import { MatButton } from '@angular/material/button';

export interface DateRange {
    from: Date;
    to: Date;
}

interface DateRangePreset {
    label: string;
    range(): DateRange;
}

function startOfDay(date: Date): Date {
    const d = new Date(date);
    d.setHours(0, 0, 0, 0);
    return d;
}

function endOfDay(date: Date): Date {
    const d = new Date(date);
    d.setHours(23, 59, 59, 999);
    return d;
}

function daysAgo(days: number): Date {
    return startOfDay(new Date(Date.now() - days * 24 * 60 * 60 * 1000));
}

function isSameDay(a: Date, b: Date): boolean {
    return (
        a.getFullYear() === b.getFullYear() &&
        a.getMonth() === b.getMonth() &&
        a.getDate() === b.getDate()
    );
}

@Component({
    selector: 'app-date-range-quick-select',
    imports: [MatButton],
    templateUrl: './date-range-quick-select.component.html',
    styleUrl: './date-range-quick-select.component.scss',
})
export class DateRangeQuickSelectComponent {
    rangeSelected = output<DateRange>();

    // The currently applied range, if any — used only to highlight whichever preset (if any)
    // it matches, so the row reflects what's actually selected instead of always looking inert.
    selectedRange = input<DateRange | null>(null);

    presets: DateRangePreset[] = [
        {
            label: 'Today',
            range: () => ({ from: startOfDay(new Date()), to: endOfDay(new Date()) }),
        },
        {
            label: 'Last 7 days',
            range: () => ({ from: daysAgo(7), to: endOfDay(new Date()) }),
        },
        {
            label: 'Last 30 days',
            range: () => ({ from: daysAgo(30), to: endOfDay(new Date()) }),
        },
        {
            label: 'This month',
            range: () => {
                const now = new Date();
                return {
                    from: new Date(now.getFullYear(), now.getMonth(), 1),
                    to: new Date(now.getFullYear(), now.getMonth() + 1, 0, 23, 59, 59, 999),
                };
            },
        },
        {
            label: 'Last month',
            range: () => {
                const now = new Date();
                return {
                    from: new Date(now.getFullYear(), now.getMonth() - 1, 1),
                    to: new Date(now.getFullYear(), now.getMonth(), 0, 23, 59, 59, 999),
                };
            },
        },
        {
            label: 'This year',
            range: () => {
                const now = new Date();
                return { from: new Date(now.getFullYear(), 0, 1), to: endOfDay(now) };
            },
        },
    ];

    apply(preset: DateRangePreset): void {
        this.rangeSelected.emit(preset.range());
    }

    isSelected(preset: DateRangePreset): boolean {
        const current = this.selectedRange();
        if (!current) {
            return false;
        }
        const presetRange = preset.range();
        return isSameDay(current.from, presetRange.from) && isSameDay(current.to, presetRange.to);
    }
}
