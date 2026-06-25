import { TestBed } from '@angular/core/testing';
import { ApolloTestingModule } from 'apollo-angular/testing';

import { BasicAuthServiceService } from './basic-auth-service.service';

describe('BasicAuthServiceService', () => {
    let service: BasicAuthServiceService;

    beforeEach(() => {
        TestBed.configureTestingModule({
            imports: [ApolloTestingModule],
        });
        service = TestBed.inject(BasicAuthServiceService);
    });

    it('should be created', () => {
        expect(service).toBeTruthy();
    });
});