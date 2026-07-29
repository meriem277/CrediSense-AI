// src/app/services/demande.service.ts
import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ClientAuthService } from './Client-auth.service';

export interface DemandeRequest {
  cin:           string;
  nom:           string;
  prenom:        string;
  dateNaissance: string;
  telephone:     string;
  adresse:       string;
  typeContrat:   string;
  nationalite:   string;
  montantCredit: string;
  dureeCredit:   string;
  clientEmail:   string;
}

export interface DemandeResponse {
  clientId:  string;
  dossierId: string;
  statut:    string;
  message:   string;
}

export interface UploadResponse {
  statut:    string;
  fichierId: string;
  type:      string;
  message:   string;
}

@Injectable({ providedIn: 'root' })
export class DemandeService {

  private readonly apiUrl = `${environment.apiUrl}/api/public`;

  constructor(
    private http: HttpClient,
    private clientAuth: ClientAuthService
  ) {}

  // ── Headers avec token client ────────────────────────────────────────
  private getHeaders(): HttpHeaders {
    const token = this.clientAuth.getToken();
    return new HttpHeaders({ Authorization: `Bearer ${token}` });
  }

  // ── Étape 1 : Créer client + dossier ─────────────────────────────────
  creerDemande(request: DemandeRequest): Observable<DemandeResponse> {
    return this.http.post<DemandeResponse>(
      `${this.apiUrl}/demande`,
      request,
      { headers: this.getHeaders() }
    );
  }

  // ── Étape 2 : Upload fichier ──────────────────────────────────────────
  uploadDocument(
    file: File,
    cin: string,
    dossierId: string,
    typeDocument: string
  ): Observable<UploadResponse> {
    const formData = new FormData();
    formData.append('file',         file);
    formData.append('cin',          cin);
    formData.append('dossierId',    dossierId);
    formData.append('typeDocument', typeDocument);

    return this.http.post<UploadResponse>(
      `${this.apiUrl}/upload`,
      formData,
      { headers: this.getHeaders() }
    );
  }

  // ── Étape 3 : Soumettre le dossier ────────────────────────────────────
  soumettreDossier(dossierId: string): Observable<any> {
    return this.http.post(
      `${this.apiUrl}/soumettre/${dossierId}`,
      {},
      { headers: this.getHeaders() }
    );
  }
}
