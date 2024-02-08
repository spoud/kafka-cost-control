import { TestBed } from '@angular/core/testing';

import { AdditionalHeadersService } from './additional-headers.service';

describe('AdditionalHeaderService', () => {
  let service: AdditionalHeadersService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(AdditionalHeadersService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
