import { Component, inject } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormField, MatInput, MatLabel } from '@angular/material/input';
import { MatButton } from '@angular/material/button';
import { CalculateTopDownGQL } from '../../generated/graphql/sdk';

@Component({
    selector: 'app-calc',
    imports: [ReactiveFormsModule, MatFormField, MatInput, MatLabel, MatFormField, MatButton],
    templateUrl: './calc.component.html',
    styleUrl: './calc.component.scss',
})
export class CalcComponent {
    private calcTopDown = inject(CalculateTopDownGQL);
    form: FormGroup;

    constructor() {
        const fb = inject(FormBuilder);
        this.form = fb.group({});
    }

    ngSubmit() {
        this.calcTopDown
            .fetch({
                request: {
                    kafkaStorageCents: 10000,
                    kafkaNetworkReadCents: 10000,
                    kafkaNetworkWriteCents: 10000,
                    remainingItems: '1234, 1234, 1234',
                },
            })
            .subscribe(data => console.log(data));
    }
}
