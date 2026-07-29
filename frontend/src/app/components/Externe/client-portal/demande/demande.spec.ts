import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Demande } from './demande';

describe('Demande', () => {
  let component: Demande;
  let fixture: ComponentFixture<Demande>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Demande],
    }).compileComponents();

    fixture = TestBed.createComponent(Demande);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
