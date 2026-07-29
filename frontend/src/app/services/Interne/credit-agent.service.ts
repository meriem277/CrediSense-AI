import { HttpClient } from "@angular/common/http";
import { Injectable } from "@angular/core";
import { Observable } from "rxjs";
import { CreditAnalysisResult } from "../../models/credit-analysis-result.model";

// credit-agent.service.ts
@Injectable({ providedIn: 'root' })
export class CreditAgentService {
  private api = 'http://localhost:8081/api/credit';

  constructor(private http: HttpClient) {}

  analyse(type: 'immobilier' | 'consommation', file: File, cin: string): Observable<CreditAnalysisResult> {
    const form = new FormData();
    form.append('file', file);
    form.append('cin', cin);
    return this.http.post<CreditAnalysisResult>(`${this.api}/analyse/${type}`, form);
  }
}
