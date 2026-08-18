import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CreditResult } from './credit-result';

describe('CreditResult', () => {
  let component: CreditResult;
  let fixture: ComponentFixture<CreditResult>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreditResult],
    }).compileComponents();

    fixture = TestBed.createComponent(CreditResult);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
