// src/app/services/fichier.service.ts

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Fichier } from '../models/fichier.model';

@Injectable({ providedIn: 'root' })
export class FichierService {

  private readonly apiUrl = `${environment.apiUrl}/api/fichiers`;

  constructor(private http: HttpClient) {}

  // ─── Upload + Conversion PDF ───────────────────────────────────────────────

  /**
   * Upload un fichier (JPG, PNG, DOCX) et le convertit en PDF côté backend.
   *
   * @param file    fichier sélectionné par l'utilisateur
   * @param cin     CIN du client
   * @param agentId UUID de l'agent connecté (récupéré depuis AuthService)
   */
  uploadAndConvert(file: File, cin: string, agentId: string): Observable<Fichier> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('cin', cin);
    formData.append('agentId', agentId);

    // Le JWT est ajouté automatiquement par jwtInterceptor
    // Ne PAS mettre Content-Type manuellement (le browser le gère pour FormData)
    return this.http.post<Fichier>(`${this.apiUrl}/upload`, formData);
  }

  // ─── CRUD ──────────────────────────────────────────────────────────────────

  getById(id: string): Observable<Fichier> {
    return this.http.get<Fichier>(`${this.apiUrl}/${id}`);
  }

  getByCin(cin: string): Observable<Fichier[]> {
    return this.http.get<Fichier[]>(`${this.apiUrl}/cin/${cin}`);
  }

  getByAgentId(agentId: string): Observable<Fichier[]> {
    return this.http.get<Fichier[]>(`${this.apiUrl}/agent/${agentId}`);
  }

  getAll(): Observable<Fichier[]> {
    return this.http.get<Fichier[]>(this.apiUrl);
  }

  delete(id: string): Observable<string> {
    return this.http.delete<string>(`${this.apiUrl}/${id}`);
  }
}
