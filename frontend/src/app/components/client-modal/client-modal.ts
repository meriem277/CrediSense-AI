import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { environment } from '../../../environments/environment';
import { HttpClient } from '@angular/common/http';

export interface ClientRequest {
  cin: string;
  nom: string;
  prenom: string;
}

export interface ClientResponse {
  id: string;
  cin: string;
  nom: string;
  prenom: string;
  createdAt: string;
}

@Component({
  selector: 'app-client-modal',
  imports: [CommonModule, FormsModule],
  templateUrl: './client-modal.html',
   standalone: true,
  styleUrl: './client-modal.scss',
})
export class ClientModal {
  @Output() closed        = new EventEmitter<void>();
  @Output() clientCreated = new EventEmitter<ClientResponse>();

  form: ClientRequest = { cin: '', nom: '', prenom: '' };

  isLoading = false;
  errorMsg  = '';
  successMsg = '';

  // Validation simple
  get cinError(): string {
    if (!this.form.cin) return '';
    if (this.form.cin.length < 7) return 'CIN trop court (min 7 caractères)';
    return '';
  }

  get isFormValid(): boolean {
    return (
      this.form.cin.trim().length >= 7 &&
      this.form.nom.trim().length >= 2 &&
      this.form.prenom.trim().length >= 2
    );
  }

  constructor(private http: HttpClient) {}

  onSubmit(): void {
    if (!this.isFormValid || this.isLoading) return;

    this.isLoading = true;
    this.errorMsg  = '';
    this.successMsg = '';

    const payload: ClientRequest = {
      cin:    this.form.cin.trim().toUpperCase(),
      nom:    this.form.nom.trim(),
      prenom: this.form.prenom.trim(),
    };

    this.http.post<ClientResponse>(`${environment.apiUrl}/api/clients`, payload)
      .subscribe({
        next: (client) => {
          this.isLoading  = false;
          this.successMsg = `Client ${client.prenom} ${client.nom} créé avec succès !`;
          this.clientCreated.emit(client);
          setTimeout(() => this.onClose(), 1500);
        },
        error: (err) => {
          this.isLoading = false;
          this.errorMsg  = err?.error?.message ?? 'Erreur lors de la création du client.';
        },
      });
  }

  onClose(): void {
    this.form      = { cin: '', nom: '', prenom: '' };
    this.errorMsg  = '';
    this.successMsg = '';
    this.closed.emit();
  }

  // Fermer en cliquant sur le backdrop
  onBackdropClick(event: MouseEvent): void {
    if ((event.target as HTMLElement).classList.contains('modal-backdrop')) {
      this.onClose();
    }
  }
}
