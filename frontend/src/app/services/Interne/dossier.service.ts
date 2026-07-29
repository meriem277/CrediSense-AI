

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Dossier, DossierRequest } from '../../models/dossier.model';

@Injectable({ providedIn: 'root' })
export class DossierService {

  private readonly apiUrl = `${environment.apiUrl}/api/dossiers`;

  constructor(private http: HttpClient) {}

  getByClientId(clientId: string): Observable<Dossier[]> {
    return this.http.get<Dossier[]>(`${this.apiUrl}/client/${clientId}`);
  }

  getById(id: string): Observable<Dossier> {
    return this.http.get<Dossier>(`${this.apiUrl}/${id}`);
  }

  create(request: DossierRequest): Observable<Dossier> {
    return this.http.post<Dossier>(this.apiUrl, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  getAll(): Observable<any[]> {
    return this.http.get<any[]>(this.apiUrl);
  }

   getByStatut(statut: string): Observable<any[]> {
    return this.http.get<any[]>(`${this.apiUrl}/statut/${statut}`);
  }

   updateStatut(id: string, statut: string, commentaire = ''): Observable<any> {
    return this.http.put(`${this.apiUrl}/${id}/statut`, { statut, commentaire });
  }
  // ✅ Récupère tous les dossiers portail (soumis par les clients)
getAllPortail(): Observable<any[]> {
  return this.http.get<any[]>(`${environment.apiUrl}/api/dossiers`);
}

getFichiersByDossier(dossierId: string): Observable<any[]> {
  return this.http.get<any[]>(
    `${environment.apiUrl}/api/dossiers/${dossierId}/fichiers`
  );
}
}
