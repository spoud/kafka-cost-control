import {Pipe, PipeTransform} from '@angular/core';
import {IntlDateService} from '../services/intl-date.service';

@Pipe({
    name: 'intlDate'
})
export class IntlDatePipe implements PipeTransform {

    constructor(private intlDateService: IntlDateService
    ) {
    }

    transform(date: Date | string, timeZone?: string): string | null {
        return this.intlDateService.transform(date, timeZone);
    }

}
