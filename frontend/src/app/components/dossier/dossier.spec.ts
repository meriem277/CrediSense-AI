import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Dossier } from './dossier';

describe('Dossier', () => {
  let component: Dossier;
  let fixture: ComponentFixture<Dossier>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Dossier],
    }).compileComponents();

    fixture = TestBed.createComponent(Dossier);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
