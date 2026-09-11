import { TestBed } from '@angular/core/testing';
import { ApolloTestingController, ApolloTestingModule } from 'apollo-angular/testing';
import { AssistantStatusService } from './assistant-status.service';

const tick = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * This service feeds the app shell's navigation, so a failure here must cost one optional menu
 * entry rather than the whole sidebar — reading a failed resource's value() throws.
 */
describe('AssistantStatusService', () => {
    let controller: ApolloTestingController;
    let service: AssistantStatusService;

    beforeEach(() => {
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ imports: [ApolloTestingModule] });
        service = TestBed.inject(AssistantStatusService);
        controller = TestBed.inject(ApolloTestingController);
    });

    it('is unavailable while still loading, so the entry does not flicker', () => {
        expect(service.available()).toBe(false);
        expect(service.loading()).toBe(true);
    });

    it('reports unavailable, rather than throwing, when the query fails', async () => {
        // let the resource kick off before failing it
        service.available();
        await tick();

        controller
            .expectOne(op => op.operationName === 'assistantStatus')
            .networkError(new Error('backend down'));
        await tick();

        expect(() => service.available()).not.toThrow();
        expect(service.available()).toBe(false);
        expect(() => service.reason()).not.toThrow();
    });
});
