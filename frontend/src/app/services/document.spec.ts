import { DocumentService } from './document.service';
import { TestBed } from '@angular/core/testing';


describe('Document', () => {
  let service: Document;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(Document);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
