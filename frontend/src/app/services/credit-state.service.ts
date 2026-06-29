// src/app/services/credit-state.service.ts
import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { CreditAnalysisResult } from '../models/credit-analysis-result.model';

@Injectable({ providedIn: 'root' })
export class CreditStateService {
  private resultSubject = new BehaviorSubject<CreditAnalysisResult | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);
  private navigateToScoreSubject = new BehaviorSubject<boolean>(false);

  // Observable that components can subscribe to when they want to auto-navigate to the score tab
  navigateToScore$ = this.navigateToScoreSubject.asObservable();

  result$  = this.resultSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();

  setLoading(v: boolean)               { this.loadingSubject.next(v); }
  setResult(r: CreditAnalysisResult)   { this.resultSubject.next(r); }
  clear()                              { this.resultSubject.next(null); }
  triggerNavigateToScore()             { this.navigateToScoreSubject.next(true); }
  resetNavigateToScore()               { this.navigateToScoreSubject.next(false); }
}
