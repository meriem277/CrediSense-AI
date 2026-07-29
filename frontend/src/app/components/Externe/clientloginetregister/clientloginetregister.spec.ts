import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Clientloginetregister } from './clientloginetregister';

describe('Clientloginetregister', () => {
  let component: Clientloginetregister;
  let fixture: ComponentFixture<Clientloginetregister>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Clientloginetregister],
    }).compileComponents();

    fixture = TestBed.createComponent(Clientloginetregister);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
