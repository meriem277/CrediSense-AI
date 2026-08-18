import { ComponentFixture, TestBed } from '@angular/core/testing';

import { UploadSection } from './upload-section';

describe('UploadSection', () => {
  let component: UploadSection;
  let fixture: ComponentFixture<UploadSection>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UploadSection],
    }).compileComponents();

    fixture = TestBed.createComponent(UploadSection);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
