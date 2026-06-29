import { HttpClient } from '@angular/common/http';
import { Component, Input, AfterViewInit, ChangeDetectorRef } from '@angular/core';
import { environment } from '../../../environments/environment';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

@Component({
  selector: 'app-chat-assistant',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-assistant.html',
  styleUrl: './chat-assistant.scss',
})
export class ChatAssistant implements AfterViewInit {

  @Input() cin:       string = '';
  @Input() dossierId: string = '';

  messages: ChatMessage[] = [];
  question  = '';
  loading   = false;

  constructor(
    private http: HttpClient,
    private cdr: ChangeDetectorRef
  ) {}

  ngAfterViewInit(): void {
    setTimeout(() => {
      if (this.dossierId && this.messages.length === 0) {
        this.messages = [{
          role: 'assistant',
          content: 'Bonjour ! Je suis CrediSense. Posez-moi une question sur ce dossier de crédit.',
          timestamp: new Date()
        }];
        this.cdr.detectChanges();
      }
    }, 0);
  }

  envoyer(): void {
    const q = this.question.trim();
    if (!q || this.loading) return;

    if (!this.dossierId) {
      this.messages.push({
        role: 'assistant',
        content: 'Erreur : aucun dossier sélectionné.',
        timestamp: new Date()
      });
      this.cdr.detectChanges();
      return;
    }

    this.messages.push({ role: 'user', content: q, timestamp: new Date() });
    this.question = '';
    this.loading  = true;
    this.cdr.detectChanges();

    this.http.post<{ reponse: string }>(
      `${environment.apiUrl}/api/chatbot/question`,
      {
        cin:       this.cin       || null,
        dossierId: this.dossierId || null,
        question:  q
      }
    ).subscribe({
      next: (res) => {
        this.messages.push({
          role: 'assistant',
          content: res.reponse,
          timestamp: new Date()
        });
        this.loading = false;
        this.cdr.detectChanges();
        this.scrollToBottom();
      },
      error: (err) => {
        console.error('Erreur chatbot:', err);
        this.messages.push({
          role: 'assistant',
          content: 'Désolé, une erreur est survenue. Veuillez réessayer.',
          timestamp: new Date()
        });
        this.loading = false;
        this.cdr.detectChanges();
      }
    });
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.envoyer();
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = document.querySelector('.chat-messages');
      if (el) el.scrollTop = el.scrollHeight;
    }, 50);
  }
}
