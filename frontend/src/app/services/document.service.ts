import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AnalysisResponse } from '../models/analysis.model';

@Injectable({ providedIn: 'root' })
export class DocumentService {

  private base = 'http://localhost:8081/api';

  constructor(private http: HttpClient) {}

  uploadFile(file: File): Observable<AnalysisResponse> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<AnalysisResponse>(`${this.base}/upload`, form);
  }

  getCreditScore(file: File): Observable<AnalysisResponse> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<AnalysisResponse>(`${this.base}/credit-score`, form);
  }

  chat(message: string, context: string): Observable<string> {
    return this.http.post<string>(`${this.base}/chat`,
      { message, context }, { responseType: 'text' as any });
  }
}
