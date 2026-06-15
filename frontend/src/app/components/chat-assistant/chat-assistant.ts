import { HttpClient } from '@angular/common/http';
import { Component, Input, OnInit } from '@angular/core';
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
export class ChatAssistant implements OnInit {

  @Input() cin:       string = '';
  @Input() dossierId: string = '';

  messages: ChatMessage[] = [];
  question  = '';
  loading   = false;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    setTimeout(() => {
      if (this.dossierId && this.messages.length === 0) {
        this.messages = [{
          role: 'assistant',
          content: 'Bonjour ! Je suis CrediSense. Posez-moi une question sur ce dossier de crédit.',
          timestamp: new Date()
        }];
      }
    }, 100);
  }

  envoyer(): void {
    const q = this.question.trim();
    if (!q || this.loading) return;

    this.messages.push({ role: 'user', content: q, timestamp: new Date() });
    this.question = '';
    this.loading  = true;

    this.http.post<{ reponse: string }>(
      `${environment.apiUrl}/api/chatbot/question`,
      { cin: this.cin, dossierId: this.dossierId, question: q }
    ).subscribe({
      next: (res) => {
        this.messages.push({
          role: 'assistant',
          content: res.reponse,
          timestamp: new Date()
        });
        this.loading = false;
        this.scrollToBottom();
      },
      error: () => {
        this.messages.push({
          role: 'assistant',
          content: 'Désolé, une erreur est survenue. Veuillez réessayer.',
          timestamp: new Date()
        });
        this.loading = false;
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