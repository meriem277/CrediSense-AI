import { Component, OnInit, OnDestroy, ChangeDetectorRef, Input } from '@angular/core';
import { CommonModule }                  from '@angular/common';
import { HttpClient }                    from '@angular/common/http';
import { Subscription, combineLatest }   from 'rxjs';
import { CreditAnalysisResult }          from '../../models/credit-analysis-result.model';
import { CreditStateService }            from '../../services/credit-state.service';
import { environment }                   from '../../../environments/environment';

@Component({
  selector: 'app-credit-result',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './credit-result.html',
  styleUrl:    './credit-result.scss',
})
export class CreditResult implements OnInit, OnDestroy {
  @Input() dossierId: string | null = null;
  @Input() cin: string | null = null;

  result:  CreditAnalysisResult | null = null;
  loading  = false;
  private subs = new Subscription();

  // ── Envoi du résultat au client ──
  sendingEmail = false;
  emailSent = false;
  sendEmailError = '';

  constructor(
    private creditState: CreditStateService,
    private cdr: ChangeDetectorRef,
    private http: HttpClient
  ) {}

  ngOnInit() {
    this.subs.add(
      combineLatest([
        this.creditState.result$,
        this.creditState.loading$
      ]).subscribe(([result, loading]) => {
        this.result  = result;
        this.loading = loading;
        // Réinitialise l'état d'envoi si un nouveau résultat arrive
        if (result) {
          this.emailSent = false;
          this.sendEmailError = '';
        }
        this.cdr.detectChanges();
      })
    );
  }

  ngOnDestroy() { this.subs.unsubscribe(); }

  get eligibilityColor(): string {
    return {
      'ELIGIBLE':     'color-eligible',
      'REFUS':        'color-refus',
      'CONDITIONNEL': 'color-conditionnel',
      'INDETERMINE':  'color-indetermine',
    }[this.result?.eligibility ?? 'INDETERMINE'] ?? 'color-indetermine';
  }

  formatMetricKey(key: string): string {
    if (!key) return '';
    const k = key.toString().toLowerCase();
    const map: Record<string,string> = {
      'dti': "Taux d'endettement",
      'ltv': 'Quotité financement',
      'monthlyincome': 'Revenu mensuel net',
      'existingdebts': 'Dettes existantes',
      'requestedamount': 'Montant demandé',
      'propertyvalue': 'Valeur du bien',
      'duration': 'Durée (mois)',
      'monthlypayment': 'Mensualité estimée',
      'personalcontribution': 'Apport personnel',
      'employmenttype': 'Type d\'emploi',
      'employmentyears': 'Ancienneté',
      'creditpurpose': 'Objet du crédit',
      'applicableRate': 'Taux applicable',
      'maxAllowedAmount': 'Montant max autorisé'
    };
    // direct match
    if (map[k]) return map[k];
    // contains checks
    if (k.includes('dti')) return map['dti'];
    if (k.includes('ltv')) return map['ltv'];
    if (k.includes('income') || k.includes('revenu')) return map['monthlyincome'];
    if (k.includes('amount') || k.includes('montant')) return map['requestedamount'];
    if (k.includes('debt') || k.includes('dettes')) return map['existingdebts'];
    if (k.includes('payment') || k.includes('mensual')) return map['monthlypayment'];
    if (k.includes('duration') || k.includes('duree')) return map['duration'];
    if (k.includes('property') || k.includes('valeur')) return map['propertyvalue'];
    if (k.includes('employment') || k.includes('emploi')) return map['employmenttype'];
    return key;
  }

  getRiskClass(level: string): string {
    const l = (level || '').toString().toUpperCase();
    return {
      'HIGH': 'risk-high',
      'MEDIUM': 'risk-medium',
      'LOW': 'risk-low'
    }[l] || 'risk-medium';
  }

  formatValue(key: string, value: any): string {
    if (value === null || value === undefined) return '-';
    const k = (key || '').toString().toLowerCase();
    let num = typeof value === 'number' ? value : parseFloat(value);
    // percentages
    if (k.includes('dti') || k.includes('ltv') || k.includes('rate')) {
      if (isNaN(num)) return String(value);
      return `${Math.round(num * 100) / 100}%`;
    }
    // money-like
    if (!isNaN(num) && (k.includes('amount') || k.includes('income') || k.includes('debts') || k.includes('payment') || k.includes('contribution') || k.includes('value') )) {
      try {
        return new Intl.NumberFormat('fr-TN', { maximumFractionDigits: 2 }).format(num) + ' TND';
      } catch (e) {
        return num.toLocaleString() + ' TND';
      }
    }
    if (!isNaN(num) && k.includes('duration')) {
      return `${num} mois`;
    }
    return String(value);
  }

  // Helpers to normalize risk item structure
  getRiskLevel(r: any): string {
    if (!r) return 'LOW';
    if (typeof r === 'string') {
      const upper = r.toUpperCase();
      if (upper.startsWith('HIGH')) return 'HIGH';
      if (upper.startsWith('MEDIUM') || upper.startsWith('MED')) return 'MEDIUM';
      if (upper.startsWith('LOW')) return 'LOW';
      try { return JSON.parse(r).level ?? 'MEDIUM'; } catch { return 'MEDIUM'; }
    }
    return (r.level ?? r['level'] ?? 'MEDIUM').toString().toUpperCase();
  }

  getRiskDescription(r: any): string {
    if (!r) return '';
    if (typeof r === 'string') {
      const colonIdx = r.indexOf(':');
      if (colonIdx > -1 && colonIdx < 10) return r.substring(colonIdx + 1).trim();
      try { return JSON.parse(r).description ?? r; } catch { return r; }
    }
    return r.description ?? r['description'] ?? r.desc ?? '';
  }

  getRiskSource(r: any): string | null {
    if (!r) return null;
    if (typeof r === 'string') {
      try { return JSON.parse(r).source ?? null; } catch { return null; }
    }
    return r.source ?? r['source'] ?? null;
  }

  // Plan helpers
  getPlanPriority(p: any, idx: number): number {
    if (!p) return idx + 1;
    if (typeof p === 'string') return idx + 1;
    return p.priority ?? (idx + 1);
  }

  getPlanAction(p: any): string {
    if (!p) return '';
    if (typeof p === 'string') {
      try {
        const parsed = JSON.parse(p);
        return parsed.action ?? parsed.description ?? p;
      } catch { return p; }
    }
    return p.action ?? p['action'] ?? p.description ?? p['description'] ?? '';
  }

  getPlanRationale(p: any): string {
    if (!p) return '';
    if (typeof p === 'string') {
      try { return JSON.parse(p).rationale ?? ''; } catch { return ''; }
    }
    return p.rationale ?? p['rationale'] ?? '';
  }

  getPlanSource(p: any): string | null {
    if (!p) return null;
    if (typeof p === 'string') return null;
    return p.source ?? p['source'] ?? null;
  }

  // ── Envoi du résultat au client par email ──
  sendResultToClient(): void {
    if (!this.result || this.sendingEmail || this.emailSent) return;

    if (!this.dossierId) {
      this.sendEmailError = 'Identifiant du dossier manquant.';
      return;
    }

    this.sendingEmail = true;
    this.sendEmailError = '';

    const payload = {
      dossierId: this.dossierId,
      cin: this.cin,
      eligibility: this.result.eligibility,
      eligibilityScore: this.result.eligibilityScore,
      creditType: this.result.creditType,
      risks: this.result.risks,
      recommendedPlan: this.result.recommendedPlan,
      rawExplanation: this.result.rawExplanation
    };

    this.http.post(`${environment.apiUrl}/api/dossiers/${this.dossierId}/send-result-email`, payload)
      .subscribe({
        next: () => {
          this.sendingEmail = false;
          this.emailSent = true;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.sendingEmail = false;
          this.sendEmailError = "Échec de l'envoi. Veuillez réessayer.";
          console.error('Erreur envoi email résultat:', err);
          this.cdr.detectChanges();
        }
      });
  }
}
