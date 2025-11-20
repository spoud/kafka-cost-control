import { Pipe, PipeTransform, inject } from '@angular/core';
import { IntlDateService } from '../services/intl-date.service';

@Pipe({
    name: 'intlDate',
})
export class IntlDatePipe implements PipeTransform {
    private intlDateService = inject(IntlDateService);

    transform(date: Date | string, timeZone?: string): string | null {
        return this.intlDateService.transform(date, timeZone);
    }
}
