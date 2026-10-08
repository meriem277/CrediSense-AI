import { Component, OnInit, OnDestroy, OnChanges, SimpleChanges, ChangeDetectorRef, Input } from '@angular/core';
import { CommonModule }                  from '@angular/common';
import { HttpClient }                    from '@angular/common/http';
import { Subscription, combineLatest }   from 'rxjs';
import { AuditLigne, CreditAnalysisResult, CritereScore, mapperResultat } from '../../../models/credit-analysis-result.model';
import { CreditStateService }            from '../../../services/credit-state.service';
import { environment }                   from '../../../../environments/environment';
import { ExportButton } from '../export-button/export-button';

@Component({
  selector: 'app-credit-result',
  standalone: true,
  imports: [CommonModule,ExportButton],
  templateUrl: './credit-result.html',
  styleUrl:    './credit-result.scss',
})
export class CreditResult implements OnInit, OnDestroy, OnChanges {
  @Input() dossierId: string | null = null;
  @Input() cin: string | null = null;

  result:  CreditAnalysisResult | null = null;
  loading  = false;
  private subs = new Subscription();

  // ── Validation de la réponse avant envoi, et journal du dossier ──
  validationEnCours = false;
  erreurValidation = '';
  journal: AuditLigne[] = [];

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
          this.erreurValidation = '';
          this.chargerJournal();
        }
        this.cdr.detectChanges();
      })
    );
  }

  // ✅ Nouveau — se déclenche à chaque changement de dossier consulté
  // (y compris le tout premier binding, avant ngOnInit). Va chercher le
  // résultat déjà sauvegardé en base pour ce dossier, pour ne plus dépendre
  // uniquement de creditState (mémoire de session, vidée en cas de retour
  // sur un dossier déjà analysé).
  ngOnChanges(changes: SimpleChanges): void {
    if (changes['dossierId'] && this.dossierId) {
      this.loadStoredResult(this.dossierId);
    }
  }

  private loadStoredResult(dossierId: string): void {
    this.http.get<any>(`${environment.apiUrl}/api/dossiers/${dossierId}/resultat`)
      .subscribe({
        next: (data) => {
          if (!data) return;

          // ✅ Repasse par creditState.setResult() pour rester sur le même
          // flux que _setScore() côté upload-section — le template n'a rien
          // à changer, il lit toujours creditState.result$.
          this.creditState.setResult(mapperResultat(data));
        },
        error: () => {
          // 404 normal si ce dossier n'a jamais été analysé — on laisse
          // l'écran par défaut ("Uploadez un document puis cliquez sur
          // Score Crédit") s'afficher, pas d'action à prendre.
        }
      });
  }

  ngOnDestroy() { this.subs.unsubscribe(); }

  get eligibilityColor(): string {
    return {
      'ELIGIBLE':     'color-eligible',
      'REFUS':        'color-refus',
      'CONDITIONNEL': 'color-conditionnel',
      'A_COMPLETER':  'color-acompleter',
      'INDETERMINE':  'color-indetermine',
    }[this.result?.eligibility ?? 'INDETERMINE'] ?? 'color-indetermine';
  }

  /** Libellé affiché : le code interne « A_COMPLETER » n'est pas lisible tel quel. */
  get eligibilityLabel(): string {
    const e = this.result?.eligibility ?? 'INDETERMINE';
    return ({
      'ELIGIBLE':     'ÉLIGIBLE',
      'REFUS':        'REFUS',
      'CONDITIONNEL': 'CONDITIONNEL',
      'A_COMPLETER':  'À COMPLÉTER',
      'INDETERMINE':  'INDÉTERMINÉ',
    } as Record<string, string>)[e] ?? e;
  }

  // ── Contrôles réglementaires ──────────────────────────────────────────────
  controleClass(statut: string): string {
    return ({ 'OK': 'ctl-ok', 'ATTENTION': 'ctl-warn', 'KO': 'ctl-ko', 'A_VERIFIER': 'ctl-todo' } as Record<string, string>)[statut] ?? 'ctl-todo';
  }

  controleLabel(statut: string): string {
    return ({ 'OK': 'Conforme', 'ATTENTION': 'Attention', 'KO': 'Non conforme', 'A_VERIFIER': 'À vérifier' } as Record<string, string>)[statut] ?? statut;
  }

  /** Chiffres calculés par le moteur : s'ils manquent, on le dit (jamais « 0 », jamais une case vide). */
  private readonly CLES_CALCULEES = ['dti', 'monthlypayment', 'existingdebts'];

  /** Un chiffre calculé qui vaut 0 est un chiffre qui n'a pas été calculé : une mensualité nulle n'existe pas. */
  private estManquante(cle: string, valeur: any): boolean {
    if (valeur === null || valeur === undefined) return true;
    const k = cle.toLowerCase();
    return (k === 'monthlypayment' || k === 'dti') && Number(valeur) === 0;
  }

  private valeurNonCalculee(cle: string): string {
    const k = cle.toLowerCase();
    if (k === 'monthlypayment') return 'Non calculée';
    if (k === 'existingdebts')  return 'Inconnues';
    return 'Non calculé';
  }

  /**
   * Cases des chiffres clés. Une valeur inconnue n'est jamais affichée comme 0 ni comme une case
   * vide : les chiffres calculés (mensualité, taux d'endettement, dettes) disent « Non calculé »,
   * les autres champs absents ne sont simplement pas affichés.
   */
  get metriquesAffichees(): { cle: string; libelle: string; valeur: string; manquante: boolean }[] {
    const metriques = (this.result?.financialMetrics ?? {}) as Record<string, any>;
    return Object.entries(metriques)
      .map(([cle, valeur]) => ({ cle, valeur, manquante: this.estManquante(cle, valeur) }))
      .filter(m => !m.manquante || this.CLES_CALCULEES.includes(m.cle.toLowerCase()))
      .map(m => ({
        cle: m.cle,
        libelle: this.formatMetricKey(m.cle),
        manquante: m.manquante,
        valeur: m.manquante ? this.valeurNonCalculee(m.cle) : this.formatValue(m.cle, m.valeur),
      }));
  }

  // ── Visualisation ─────────────────────────────────────────────────────────
  // Seuils d'endettement du moteur de règles (agent_service.py : DTI_ACCEPTABLE et DTI_MAX). Ils ne servent
  // ici qu'à DESSINER les zones de la règle graduée ; les verdicts viennent toujours du service.
  readonly SEUIL_DTI_ACCEPTABLE = 30;
  readonly SEUIL_DTI_MAX        = 35;
  readonly ECHELLE_DTI          = 50;
  readonly RAYON_SCORE          = 52;

  private metrique(cle: string): number | null {
    const v = Number((this.result?.financialMetrics as Record<string, any> | undefined)?.[cle]);
    return Number.isFinite(v) && v > 0 ? v : null;
  }

  /** Longueur de l'arc de la jauge de score (cercle de rayon 52). */
  get scoreTrait(): string {
    const circonference = 2 * Math.PI * this.RAYON_SCORE;
    const score = Math.max(0, Math.min(100, Number(this.result?.eligibilityScore ?? 0)));
    return `${(circonference * score) / 100} ${circonference}`;
  }

  get classeScore(): string {
    const s = Number(this.result?.eligibilityScore ?? 0);
    return s >= 70 ? 'score-vert' : s >= 40 ? 'score-orange' : 'score-rouge';
  }

  get dtiValeur(): number | null { return this.metrique('dti'); }

  /** Position du repère sur la règle graduée de 0 à 50 %, en pourcentage de la largeur. */
  get dtiPosition(): number {
    return Math.min(this.dtiValeur ?? 0, this.ECHELLE_DTI) / this.ECHELLE_DTI * 100;
  }

  get dtiStatut(): 'ok' | 'warn' | 'ko' {
    const d = this.dtiValeur ?? 0;
    return d < this.SEUIL_DTI_ACCEPTABLE ? 'ok' : d <= this.SEUIL_DTI_MAX ? 'warn' : 'ko';
  }

  get dtiMessage(): string {
    const d = this.dtiValeur;
    if (d === null) return '';
    const texte = this.pct(d);
    return this.dtiStatut === 'ok'   ? `${texte} : sous le seuil de ${this.SEUIL_DTI_ACCEPTABLE} %`
         : this.dtiStatut === 'warn' ? `${texte} : zone de risque (${this.SEUIL_DTI_ACCEPTABLE} à ${this.SEUIL_DTI_MAX} %)`
         :                             `${texte} : au-dessus du maximum de ${this.SEUIL_DTI_MAX} %`;
  }

  get mensualiteDemandee(): number | null { return this.metrique('monthlyPayment'); }
  get capaciteMensuelle(): number | null {
    const v = Number(this.result?.capacity?.maxMonthlyPayment);
    return Number.isFinite(v) && v > 0 ? v : null;
  }

  /** Largeur des deux barres « mensualité demandée » / « capacité », rapportées à la plus grande. */
  largeurComparaison(valeur: number | null): number {
    const max = Math.max(this.mensualiteDemandee ?? 0, this.capaciteMensuelle ?? 0);
    return valeur && max ? Math.round(valeur / max * 100) : 0;
  }

  get depasseLaCapacite(): boolean {
    return this.mensualiteDemandee !== null && this.capaciteMensuelle !== null
        && this.mensualiteDemandee > this.capaciteMensuelle;
  }

  pct(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined || isNaN(Number(valeur))) return '—';
    return `${Number(valeur).toFixed(2).replace('.', ',')} %`;
  }

  // Contrôles réglementaires : compteurs et icônes (jamais la couleur seule)
  get compteursControles(): { ok: number; warn: number; ko: number; todo: number } {
    const c = { ok: 0, warn: 0, ko: 0, todo: 0 };
    for (const k of this.result?.regulatoryChecks ?? []) {
      if (k.status === 'OK') c.ok++;
      else if (k.status === 'ATTENTION') c.warn++;
      else if (k.status === 'KO') c.ko++;
      else c.todo++;
    }
    return c;
  }

  iconeControle(statut: string): string {
    return ({ 'OK': '✓', 'ATTENTION': '!', 'KO': '✕', 'A_VERIFIER': '?' } as Record<string, string>)[statut] ?? '?';
  }

  // Simulation par durée : une colonne par durée, hauteur proportionnelle au taux d'endettement
  hauteurDti(valeur: number): number {
    return Math.min(Math.max(Number(valeur) || 0, 0), this.ECHELLE_DTI) / this.ECHELLE_DTI * 100;
  }

  get ligne30(): number { return this.SEUIL_DTI_ACCEPTABLE / this.ECHELLE_DTI * 100; }
  get ligne35(): number { return this.SEUIL_DTI_MAX / this.ECHELLE_DTI * 100; }

  // Propositions d'ajustement : écart par rapport à la demande
  get montantDemande(): number | null { return this.metrique('requestedAmount'); }
  get dureeDemandee(): number | null { return this.metrique('duration'); }

  private entier(n: number): string {
    return new Intl.NumberFormat('fr-TN', { maximumFractionDigits: 0 }).format(Math.abs(n));
  }

  ecartMontant(montant: number): string {
    const demande = this.montantDemande;
    if (demande === null) return '';
    const ecart = Math.round(montant - demande);
    return ecart === 0 ? 'montant demandé conservé' : `${ecart > 0 ? '+' : '−'}${this.entier(ecart)} DT par rapport à la demande`;
  }

  ecartDuree(duree: number): string {
    const demande = this.dureeDemandee;
    if (demande === null) return '';
    const ecart = duree - demande;
    return ecart === 0 ? 'durée demandée conservée' : `${ecart > 0 ? '+' : '−'}${Math.abs(ecart)} mois`;
  }

  /** Couleur de la barre d'un critère : proportion des points obtenus (le texte « 36 / 40 » porte aussi l'information). */
  classePointsCritere(critere: CritereScore): string {
    if (!critere.connu || !critere.maximum || critere.points === null) return 'pts-inconnu';
    const part = critere.points / critere.maximum;
    return part >= 0.7 ? 'pts-bon' : part >= 0.4 ? 'pts-moyen' : 'pts-faible';
  }

  /** Montant en dinars avec 3 décimales (millimes) : 2100 -> « 2 100,000 DT ». */
  formatDT(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined || isNaN(Number(valeur))) return '—';
    return new Intl.NumberFormat('fr-TN', { minimumFractionDigits: 3, maximumFractionDigits: 3 }).format(Number(valeur)) + ' DT';
  }

  // ── Points forts / vigilance : texte simple ou objet {title, detail, source} ─
  pointTitre(p: any): string {
    return typeof p === 'string' ? p : (p?.title ?? p?.detail ?? '');
  }

  pointDetail(p: any): string {
    return typeof p === 'string' ? '' : (p?.title ? (p?.detail ?? '') : '');
  }

  pointSource(p: any): string {
    return typeof p === 'string' ? '' : (p?.source ?? '');
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
      'applicablerate': 'Taux applicable',
      'maxallowedamount': 'Montant max autorisé',
      'clientage': 'Âge du client',
      'contracttype': 'Type de contrat',
      'employmentstartdate': 'Date d\'embauche',
      'paymentincidents': 'Incidents de paiement'
    };
    if (map[k]) return map[k];
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
    // Un nombre d'incidents n'est pas un montant (« payment » est dans le nom de la clé)
    if (k === 'paymentincidents' || k === 'contracttype' || k === 'employmentstartdate') return String(value);
    if (k === 'clientage' && !isNaN(num)) return `${num} ans`;
    if (k.includes('dti') || k.includes('ltv') || k.includes('rate')) {
      if (isNaN(num)) return String(value);
      return `${Math.round(num * 100) / 100}%`;
    }
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

  // ── Envoi automatique de la réponse au client ──────────────────────────────

  /** La réponse est déjà partie chez le client (automatiquement ou par l'agent). */
  get reponseDejaEnvoyee(): boolean {
    return this.result?.notification?.statut === 'ENVOYE';
  }

  /** La réponse attend un agent : « Valider et envoyer », ou un envoi différé encore annulable. */
  get enAttenteValidation(): boolean {
    return this.result?.notification?.statut === 'EN_ATTENTE_VALIDATION';
  }

  get envoiProgramme(): boolean {
    return this.result?.notification?.statut === 'PROGRAMME';
  }

  get reponseEnAttente(): boolean {
    return this.enAttenteValidation || this.envoiProgramme;
  }

  get classeNotification(): 'ok' | 'erreur' | 'attente' | 'info' {
    const statut = this.result?.notification?.statut;
    if (statut === 'ENVOYE') return 'ok';
    if (statut === 'ECHEC') return 'erreur';
    if (statut === 'EN_ATTENTE_VALIDATION' || statut === 'PROGRAMME') return 'attente';
    return 'info';
  }

  get titreNotification(): string {
    switch (this.result?.notification?.statut) {
      case 'ENVOYE':     return 'Réponse envoyée au client';
      case 'ECHEC':      return 'L\'envoi automatique a échoué';
      case 'EN_ATTENTE_VALIDATION': return 'Réponse en attente de votre validation';
      case 'PROGRAMME':  return 'Envoi programmé';
      case 'ANNULE':     return 'Envoi annulé';
      case 'NON_ENVOYE': return 'Réponse non envoyée';
      case 'DESACTIVE':  return 'Envoi automatique désactivé';
      default:           return 'Aucun e-mail envoyé';
    }
  }

  get detailNotification(): string {
    const n = this.result?.notification;
    if (!n) return '';
    if (n.statut === 'ENVOYE') {
      const quand = this.dateLisible(n.envoyeAt);
      return `E-mail envoyé automatiquement à ${n.destinataire ?? 'le client'}` +
             `${quand ? ' le ' + quand : ''}, avec le rapport PDF en pièce jointe.`;
    }
    if (n.statut === 'ECHEC') {
      return `${n.detail ?? ''} Utilisez « Envoyer la réponse au client » pour réessayer.`.trim();
    }
    if (n.statut === 'EN_ATTENTE_VALIDATION') {
      const a = n.destinataire ? ` Destinataire : ${n.destinataire}.` : '';
      return `Le client ne recevra rien tant que vous n'avez pas validé.${a}`;
    }
    return n.detail ?? '';
  }

  // ── Valider et envoyer / ne pas envoyer ────────────────────────────────────

  /** « Valider et envoyer » (ou « Envoyer maintenant » pour un envoi différé). */
  validerEnvoi(): void {
    if (!this.result || !this.dossierId || this.validationEnCours || !this.reponseEnAttente) return;
    this.validationEnCours = true;
    this.erreurValidation = '';

    // On envoie la décision que l'agent a sous les yeux : si une nouvelle analyse l'a changée, le serveur refuse
    this.http.post<any>(`${environment.apiUrl}/api/dossiers/${this.dossierId}/reponse/valider`,
      { decision: this.result.eligibility }
    ).subscribe({
      next: (reponse) => this.apresAction(reponse),
      error: (err) => this.echecAction(err, 'La réponse n\'a pas pu être validée. Veuillez réessayer.'),
    });
  }

  /** « Ne pas envoyer » / « Annuler l'envoi » : le client ne recevra rien pour cette décision. */
  annulerEnvoi(): void {
    if (!this.result || !this.dossierId || this.validationEnCours || !this.reponseEnAttente) return;
    this.validationEnCours = true;
    this.erreurValidation = '';

    this.http.post<any>(`${environment.apiUrl}/api/dossiers/${this.dossierId}/reponse/annuler`, {})
      .subscribe({
        next: (reponse) => this.apresAction(reponse),
        error: (err) => this.echecAction(err, 'L\'envoi n\'a pas pu être annulé. Veuillez réessayer.'),
      });
  }

  private apresAction(reponse: any): void {
    this.validationEnCours = false;
    if (this.result && reponse?.notification) {
      this.result = { ...this.result, notification: reponse.notification };
    }
    // « success » est faux quand la validation a été prise en compte mais que l'envoi a échoué :
    // le bandeau rouge l'explique déjà, pas de message de plus
    this.chargerJournal();
    this.cdr.detectChanges();
  }

  private echecAction(err: any, messageParDefaut: string): void {
    this.validationEnCours = false;
    this.erreurValidation = err?.status === 409 && err?.error?.error ? err.error.error : messageParDefaut;
    // La situation a changé (nouvelle analyse, déjà traité) : on relit l'état réel du dossier
    if (err?.status === 409 && this.dossierId) this.loadStoredResult(this.dossierId);
    this.cdr.detectChanges();
  }

  // ── Journal d'audit ────────────────────────────────────────────────────────

  chargerJournal(): void {
    if (!this.dossierId) return;
    this.http.get<AuditLigne[]>(`${environment.apiUrl}/api/dossiers/${this.dossierId}/audit`)
      .subscribe({
        next: (lignes) => { this.journal = Array.isArray(lignes) ? lignes : []; this.cdr.detectChanges(); },
        error: () => { /* le journal est un plus : son absence n'empêche pas de travailler */ },
      });
  }

  /** « 07/10/2026 22:10 » */
  dateJournal(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    return new Intl.DateTimeFormat('fr-FR', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
    }).format(d);
  }

  acteurLisible(acteur: string | null | undefined): string {
    return !acteur || acteur === 'SYSTEME' ? 'Système' : acteur;
  }

  private dateLisible(iso: string | null | undefined): string {
    if (!iso) return '';
    const date = new Date(iso);
    if (isNaN(date.getTime())) return '';
    return new Intl.DateTimeFormat('fr-FR', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
    }).format(date);
  }

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
