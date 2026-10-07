import { HttpClient } from '@angular/common/http';
import { Component, Input, AfterViewInit, ChangeDetectorRef } from '@angular/core';
import { environment } from '../../../../environments/environment';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  /** Message d'interface (salutation, erreur) : affiché, mais jamais envoyé comme historique. */
  technique?: boolean;
}

/** Message de l'historique envoyé au serveur avec chaque question. */
export interface MessageHistorique {
  role: 'user' | 'assistant';
  content: string;
}

/** Nombre de derniers messages envoyés avec chaque question (le serveur applique la même limite). */
export const HISTORIQUE_MAX_MESSAGES = 6;

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
          timestamp: new Date(),
          technique: true
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
        timestamp: new Date(),
        technique: true
      });
      this.cdr.detectChanges();
      return;
    }

    // Mémoire de conversation : les derniers échanges, SANS la question en cours (envoyée à part)
    const historique = this.historiquePourEnvoi();

    this.messages.push({ role: 'user', content: q, timestamp: new Date() });
    this.question = '';
    this.loading  = true;
    this.cdr.detectChanges();

    this.http.post<{ reponse: string }>(
      `${environment.apiUrl}/api/chatbot/question`,
      {
        cin:       this.cin       || null,
        dossierId: this.dossierId || null,
        question:  q,
        historique
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
          timestamp: new Date(),
          technique: true
        });
        this.loading = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Derniers échanges réels de la conversation, du plus ancien au plus récent. La salutation et
   * les messages d'erreur n'en font pas partie : ils n'ont pas été dits au modèle.
   */
  historiquePourEnvoi(): MessageHistorique[] {
    return this.messages
      .filter(m => !m.technique && !!m.content?.trim())
      .slice(-HISTORIQUE_MAX_MESSAGES)
      .map(m => ({ role: m.role, content: m.content }));
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
