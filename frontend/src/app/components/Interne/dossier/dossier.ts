import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Dossier as DossierModel, DossierRequest } from '../../../models/dossier.model';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-dossier',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dossier.html',
  styleUrl: './dossier.scss',
})
export class Dossier {

  @Input()  clientId!: string;
  @Output() closed         = new EventEmitter<void>();
  @Output() dossierCreated = new EventEmitter<DossierModel>(); // ✅ DossierModel

  form: DossierRequest = {
    typeCredit: '',
    statut:     'EN_COURS',
    clientId:   '',
  };

  isLoading = false;
  errorMsg  = '';

  get isFormValid(): boolean {
    return this.form.typeCredit.trim().length > 0 &&
this.form.statut.trim().length > 0;
  }

  constructor(private http: HttpClient) {}

  onSubmit(): void {
    if (!this.isFormValid || this.isLoading) return;

    this.isLoading = true;
    this.errorMsg  = '';

    const payload: DossierRequest = {
      typeCredit: this.form.typeCredit,
      statut:     this.form.statut,
      clientId:   this.clientId,
    };

    this.http.post<DossierModel>(`${environment.apiUrl}/api/dossiers`, payload)
      .subscribe({
        next: (dossier) => {
          this.isLoading = false;
          this.dossierCreated.emit(dossier);
          this.onClose();
        },
        error: (err) => {
          this.isLoading = false;
          this.errorMsg  = err?.error?.message ?? 'Erreur lors de la création.';
        },
      });
  }

  onClose(): void {
    this.form     = { typeCredit: '', statut: 'EN_COURS', clientId: '' };
    this.errorMsg = '';
    this.closed.emit();
  }

  onBackdropClick(event: MouseEvent): void {
    if ((event.target as HTMLElement).classList.contains('modal-backdrop')) {
      this.onClose();
    }
  }
}
