import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ChatAssistant } from './chat-assistant';

describe('ChatAssistant', () => {
  let component: ChatAssistant;
  let fixture: ComponentFixture<ChatAssistant>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatAssistant],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatAssistant);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
