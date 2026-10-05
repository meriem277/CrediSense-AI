import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PipelineAnalyse } from './pipeline-analyse';

describe('PipelineAnalyse', () => {
  let component: PipelineAnalyse;
  let fixture: ComponentFixture<PipelineAnalyse>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PipelineAnalyse],
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineAnalyse);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
