import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../environments/environment';
import { Motif, PointEvolution, StatistiquesDonnees } from '../../../models/statistiques.model';

/** Les décisions, dans l'ordre où elles s'affichent. La couleur suit l'entité, jamais son rang. */
export const DECISIONS = [
  { cle: 'ELIGIBLE',     libelle: 'Éligible',     variable: '--viz-eligible' },
  { cle: 'CONDITIONNEL', libelle: 'Conditionnel', variable: '--viz-conditionnel' },
  { cle: 'REFUS',        libelle: 'Refus',        variable: '--viz-refus' },
  { cle: 'A_COMPLETER',  libelle: 'À compléter',  variable: '--viz-acompleter' },
  { cle: 'INDETERMINE',  libelle: 'Indéterminée', variable: '--viz-indetermine' },
] as const;

/** Les quatre décisions empilées dans le graphique d'évolution, du bas vers le haut. */
const PILE = ['ELIGIBLE', 'CONDITIONNEL', 'REFUS', 'A_COMPLETER'] as const;

export const PRESETS = [
  { id: '7j',  libelle: '7 jours',  jours: 7 },
  { id: '30j', libelle: '30 jours', jours: 30 },
  { id: '90j', libelle: '90 jours', jours: 90 },
  { id: '12m', libelle: '12 mois',  jours: 365 },
] as const;

/** Mêmes limites que le serveur : une erreur se corrige ici, avant la requête. */
export const JOURS_MAX = 800;

// ── Géométrie du graphique d'évolution (viewBox 640 × 260) ──────────────────
export const LARGEUR = 640;
export const HAUTEUR = 260;
const MARGE = { gauche: 38, droite: 8, haut: 10, bas: 28 };
const LARGEUR_UTILE = LARGEUR - MARGE.gauche - MARGE.droite;
const HAUTEUR_UTILE = HAUTEUR - MARGE.haut - MARGE.bas;
const BASE = MARGE.haut + HAUTEUR_UTILE;          // ordonnée de la ligne de base
const EPAISSEUR_MAX = 24;                         // une colonne ne remplit jamais sa case
const ECART = 2;                                  // 2 px de surface entre deux segments empilés
const RAYON = 4;                                  // bout de donnée arrondi, base carrée

export interface Segment {
  cle: string;
  libelle: string;
  variable: string;
  nombre: number;
  /** Tracé du segment ; l'écart de surface est déjà retiré. */
  chemin: string;
}

export interface Colonne {
  point: PointEvolution;
  x: number;               // début de la case (zone cliquable)
  largeurCase: number;
  centre: number;
  segments: Segment[];
  etiquette: string;       // libellé de l'axe (vide si on l'omet pour ne pas chevaucher)
  libelleLong: string;     // « 3 octobre » / « semaine du 6 octobre »
  description: string;     // lue par un lecteur d'écran
}

export interface Graduation {
  valeur: number;
  y: number;
}

/** Plus petit pas « rond » (1, 2, 5, 10, 20, 50…) qui tient en 5 graduations. */
export function pasRond(max: number): number {
  if (max <= 0) return 1;
  const brut = max / 4;
  const puissance = Math.pow(10, Math.floor(Math.log10(brut)));
  for (const m of [1, 2, 5, 10]) {
    if (m * puissance >= brut) return m * puissance;
  }
  return 10 * puissance;
}

@Component({
  selector: 'app-statistiques',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './statistiques.html',
  styleUrl: './statistiques.scss',
})
export class Statistiques implements OnInit {

  readonly presets = PRESETS;
  readonly decisions = DECISIONS;
  readonly largeur = LARGEUR;
  readonly hauteur = HAUTEUR;
  readonly base = BASE;
  readonly margeGauche = MARGE.gauche;
  readonly margeDroite = MARGE.droite;
  readonly hauteurTexteX = HAUTEUR - 8;

  /** Période choisie : un préréglage, ou « perso » avec les deux dates. */
  preset: string = '30j';
  du = '';
  au = '';

  stats: StatistiquesDonnees | null = null;
  chargement = false;
  erreur = '';
  erreurExport = '';
  exportEnCours = false;

  /** Colonne survolée ou focalisée : son infobulle est affichée. */
  colonneActive: Colonne | null = null;

  constructor(private http: HttpClient) {}

  // ── Calculs mémorisés ──────────────────────────────────────────────────────
  // Les listes dérivées (colonnes, barres…) sont lues par le gabarit à chaque cycle de détection.
  // Les recréer à chaque lecture détruirait et recréerait les éléments : la colonne survolée ne serait
  // jamais reconnue comme « active » et l'élément qui a le focus clavier le perdrait. On ne recalcule
  // donc que lorsque les données changent.
  private sourceMemo: StatistiquesDonnees | null | undefined = undefined;
  private valeursMemo: Record<string, unknown> = {};

  private memo<T>(nom: string, calcul: () => T): T {
    if (this.sourceMemo !== this.stats) {
      this.sourceMemo = this.stats;
      this.valeursMemo = {};
    }
    if (!(nom in this.valeursMemo)) this.valeursMemo[nom] = calcul();
    return this.valeursMemo[nom] as T;
  }

  ngOnInit(): void {
    this.appliquerPreset('30j');
  }

  // ── Période ────────────────────────────────────────────────────────────────

  appliquerPreset(id: string): void {
    const p = PRESETS.find(x => x.id === id);
    if (!p) return;
    const fin = new Date();
    const debut = new Date();
    debut.setDate(fin.getDate() - (p.jours - 1));
    this.preset = id;
    this.du = this.iso(debut);
    this.au = this.iso(fin);
    this.erreur = '';
    this.charger();
  }

  appliquerPersonnalise(): void {
    const probleme = this.problemePeriode();
    if (probleme) {
      this.erreur = probleme;
      return;
    }
    this.preset = 'perso';
    this.erreur = '';
    this.charger();
  }

  /** Message si la période saisie est inutilisable, sinon chaîne vide. */
  problemePeriode(): string {
    if (!this.du || !this.au) return 'Renseignez les deux dates.';
    const debut = new Date(this.du + 'T00:00:00');
    const fin = new Date(this.au + 'T00:00:00');
    if (isNaN(debut.getTime()) || isNaN(fin.getTime())) return 'Dates invalides.';
    if (debut > fin) return 'La date de début est après la date de fin.';
    const jours = Math.round((fin.getTime() - debut.getTime()) / 86400000) + 1;
    if (jours > JOURS_MAX) return `La période ne peut pas dépasser ${JOURS_MAX} jours.`;
    return '';
  }

  /** AAAA-MM-JJ en heure locale (toISOString décalerait la date selon le fuseau). */
  private iso(d: Date): string {
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const j = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${m}-${j}`;
  }

  charger(): void {
    // Les chiffres précédents restent affichés (estompés) pendant le rechargement : pas de saut de page
    this.chargement = true;
    this.erreur = '';
    this.http.get<StatistiquesDonnees>(`${environment.apiUrl}/api/admin/statistiques`,
      { params: { du: this.du, au: this.au } }
    ).subscribe({
      next: (donnees) => {
        this.stats = donnees;
        this.colonneActive = null;
        this.chargement = false;
      },
      error: () => {
        this.chargement = false;
        this.erreur = 'Les statistiques n\'ont pas pu être chargées. Veuillez réessayer.';
      }
    });
  }

  // ── Export Excel ───────────────────────────────────────────────────────────

  exporter(): void {
    if (this.exportEnCours || this.problemePeriode()) return;
    this.exportEnCours = true;
    this.erreurExport = '';

    this.http.get(`${environment.apiUrl}/api/admin/statistiques/export.xlsx`,
      { params: { du: this.du, au: this.au }, responseType: 'blob' }
    ).subscribe({
      next: (fichier) => {
        this.enregistrer(fichier, `Statistiques-credisense-${this.du}_${this.au}.xlsx`);
        this.exportEnCours = false;
      },
      error: () => {
        this.exportEnCours = false;
        this.erreurExport = 'L\'export Excel n\'a pas pu être généré. Veuillez réessayer.';
      }
    });
  }

  /** Déclenche l'enregistrement du fichier dans le navigateur. */
  protected enregistrer(fichier: Blob, nom: string): void {
    const adresse = URL.createObjectURL(fichier);
    const lien = document.createElement('a');
    lien.href = adresse;
    lien.download = nom;
    document.body.appendChild(lien);
    lien.click();
    lien.remove();
    URL.revokeObjectURL(adresse);
  }

  // ── Chiffres clés ──────────────────────────────────────────────────────────

  pourcent(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined) return '—';
    return `${(valeur * 100).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} %`;
  }

  /** « 12 min », « 3,5 h », « 2,3 j » : l'unité qui se lit. */
  delai(heures: number | null | undefined): string {
    if (heures === null || heures === undefined) return '—';
    if (heures < 1) return `${Math.round(heures * 60)} min`;
    if (heures < 48) return `${heures.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} h`;
    return `${(heures / 24).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} j`;
  }

  montant(valeur: number | null | undefined): string {
    if (valeur === null || valeur === undefined) return '—';
    return `${valeur.toLocaleString('fr-FR', { maximumFractionDigits: 0 })} DT`;
  }

  get aDesDecisions(): boolean {
    return !!this.stats && this.stats.decisionsTotal > 0;
  }

  // ── Répartition des décisions ──────────────────────────────────────────────

  /** Une ligne par décision ; la barre est proportionnelle à la plus grande valeur. */
  get repartition(): { cle: string; libelle: string; variable: string; nombre: number; part: string; largeur: number }[] {
    return this.memo('repartition', () => this.calculerRepartition());
  }

  private calculerRepartition(): { cle: string; libelle: string; variable: string; nombre: number; part: string; largeur: number }[] {
    if (!this.stats) return [];
    const parDecision = this.stats.parDecision ?? {};
    const maxi = Math.max(1, ...DECISIONS.map(d => parDecision[d.cle] ?? 0));
    const total = this.stats.decisionsTotal || 0;
    return DECISIONS.map(d => {
      const nombre = parDecision[d.cle] ?? 0;
      return {
        cle: d.cle, libelle: d.libelle, variable: d.variable, nombre,
        part: total > 0 ? this.pourcent(nombre / total) : '—',
        largeur: nombre > 0 ? Math.max(2, (nombre / maxi) * 100) : 0,
      };
    });
  }

  // ── Motifs ─────────────────────────────────────────────────────────────────

  /** Les 8 motifs les plus fréquents (le tableau de données les donne tous). */
  get motifsPrincipaux(): (Motif & { variable: string; libelleDecision: string; largeur: number })[] {
    return this.memo('motifs', () => this.calculerMotifs());
  }

  private calculerMotifs(): (Motif & { variable: string; libelleDecision: string; largeur: number })[] {
    const motifs = this.stats?.motifs ?? [];
    const maxi = Math.max(1, ...motifs.map(m => m.nombre));
    return motifs.slice(0, 8).map(m => ({
      ...m,
      variable: this.variableDe(m.decision),
      libelleDecision: this.libelleDe(m.decision),
      largeur: Math.max(2, (m.nombre / maxi) * 100),
    }));
  }

  variableDe(cle: string): string {
    return DECISIONS.find(d => d.cle === cle)?.variable ?? '--viz-indetermine';
  }

  libelleDe(cle: string): string {
    return DECISIONS.find(d => d.cle === cle)?.libelle ?? cle;
  }

  // ── Évolution : graphique en colonnes empilées ─────────────────────────────

  get maxEvolution(): number {
    return Math.max(0, ...(this.stats?.evolution ?? []).map(p => p.total));
  }

  get pas(): number {
    return pasRond(this.maxEvolution);
  }

  /** Sommet de l'échelle : un multiple rond du pas, jamais en dessous de la plus haute colonne. */
  get sommet(): number {
    const pas = this.pas;
    return Math.max(pas, Math.ceil(this.maxEvolution / pas) * pas);
  }

  get graduations(): Graduation[] {
    return this.memo('graduations', () => this.calculerGraduations());
  }

  private calculerGraduations(): Graduation[] {
    const pas = this.pas;
    const sommet = this.sommet;
    const lignes: Graduation[] = [];
    for (let v = 0; v <= sommet + 1e-9; v += pas) {
      lignes.push({ valeur: v, y: BASE - (v / sommet) * HAUTEUR_UTILE });
    }
    return lignes;
  }

  get colonnes(): Colonne[] {
    return this.memo('colonnes', () => this.calculerColonnes());
  }

  private calculerColonnes(): Colonne[] {
    const points = this.stats?.evolution ?? [];
    if (points.length === 0) return [];

    const sommet = this.sommet;
    const largeurCase = LARGEUR_UTILE / points.length;
    const epaisseur = Math.max(2, Math.min(EPAISSEUR_MAX, largeurCase * 0.7));
    const pasEtiquette = Math.max(1, Math.ceil(points.length / 8));
    const parSemaine = this.stats?.granularite === 'SEMAINE';

    return points.map((point, i) => {
      const xCase = MARGE.gauche + i * largeurCase;
      const xBarre = xCase + (largeurCase - epaisseur) / 2;
      const segments = this.segments(point, xBarre, epaisseur, sommet);
      return {
        point, x: xCase, largeurCase, centre: xCase + largeurCase / 2, segments,
        etiquette: i % pasEtiquette === 0 ? this.etiquetteCourte(point.date, parSemaine) : '',
        libelleLong: this.etiquetteLongue(point.date, parSemaine),
        description: this.description(point, parSemaine),
      };
    });
  }

  private segments(point: PointEvolution, x: number, largeur: number, sommet: number): Segment[] {
    const valeurs: Record<string, number> = {
      ELIGIBLE: point.eligible, CONDITIONNEL: point.conditionnel, REFUS: point.refus, A_COMPLETER: point.aCompleter,
    };
    const presents = PILE.filter(cle => valeurs[cle] > 0);
    let cumul = 0;

    return presents.map((cle, rang) => {
      const nombre = valeurs[cle];
      const hauteur = (nombre / sommet) * HAUTEUR_UTILE;
      const haut = BASE - cumul - hauteur;
      cumul += hauteur;

      // Le segment du bas touche la ligne de base ; les autres laissent 2 px de surface sous eux
      const dessine = rang === 0 ? hauteur : Math.max(1, hauteur - ECART);
      const arrondi = rang === presents.length - 1 ? Math.min(RAYON, dessine / 2) : 0;
      const decision = DECISIONS.find(d => d.cle === cle)!;
      return {
        cle, libelle: decision.libelle, variable: decision.variable, nombre,
        chemin: this.chemin(x, haut, largeur, dessine, arrondi),
      };
    });
  }

  /** Rectangle dont seuls les deux coins du haut sont arrondis (le bas reste carré, sur la base). */
  chemin(x: number, y: number, l: number, h: number, r: number): string {
    const f = (n: number) => Math.round(n * 100) / 100;
    if (r <= 0) return `M${f(x)},${f(y + h)} L${f(x)},${f(y)} L${f(x + l)},${f(y)} L${f(x + l)},${f(y + h)} Z`;
    return `M${f(x)},${f(y + h)} L${f(x)},${f(y + r)} Q${f(x)},${f(y)} ${f(x + r)},${f(y)} `
         + `L${f(x + l - r)},${f(y)} Q${f(x + l)},${f(y)} ${f(x + l)},${f(y + r)} L${f(x + l)},${f(y + h)} Z`;
  }

  private etiquetteCourte(date: string, parSemaine: boolean): string {
    const [, m, j] = date.split('-');
    return `${parSemaine ? 'sem. ' : ''}${j}/${m}`;
  }

  private etiquetteLongue(date: string, parSemaine: boolean): string {
    const d = new Date(date + 'T00:00:00');
    const texte = d.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long' });
    return parSemaine ? `semaine du ${texte}` : texte;
  }

  private description(p: PointEvolution, parSemaine: boolean): string {
    return `${this.etiquetteLongue(p.date, parSemaine)} : ${p.eligible} éligible(s), ${p.conditionnel} conditionnel(s), `
         + `${p.refus} refus, ${p.aCompleter} à compléter, total ${p.total}`;
  }

  /** Total de chaque décision sur la période : c'est ce qu'affiche la légende. */
  get totauxLegende(): { cle: string; libelle: string; variable: string; nombre: number }[] {
    return this.memo('legende', () => this.calculerLegende());
  }

  private calculerLegende(): { cle: string; libelle: string; variable: string; nombre: number }[] {
    const par = this.stats?.parDecision ?? {};
    return DECISIONS.filter(d => PILE.includes(d.cle as typeof PILE[number]))
      .map(d => ({ cle: d.cle, libelle: d.libelle, variable: d.variable, nombre: par[d.cle] ?? 0 }));
  }

  // ── Infobulle ──────────────────────────────────────────────────────────────

  activer(colonne: Colonne): void {
    this.colonneActive = colonne;
  }

  desactiver(): void {
    this.colonneActive = null;
  }

  /** Position horizontale de l'infobulle, en % de la largeur du graphique (à gauche passé 60 %). */
  get infobulle(): { gauche: number; aDroite: boolean } | null {
    const c = this.colonneActive;
    if (!c) return null;
    const pct = (c.centre / LARGEUR) * 100;
    return { gauche: pct, aDroite: pct > 60 };
  }

  valeurDe(point: PointEvolution, cle: string): number {
    switch (cle) {
      case 'ELIGIBLE':     return point.eligible;
      case 'CONDITIONNEL': return point.conditionnel;
      case 'REFUS':        return point.refus;
      case 'A_COMPLETER':  return point.aCompleter;
      default:             return 0;
    }
  }

  // ── Envois ─────────────────────────────────────────────────────────────────

  get envois(): { libelle: string; nombre: number; gravite: 'ok' | 'attention' | 'erreur' | 'neutre'; icone: string }[] {
    return this.memo('envois', () => this.calculerEnvois());
  }

  private calculerEnvois(): { libelle: string; nombre: number; gravite: 'ok' | 'attention' | 'erreur' | 'neutre'; icone: string }[] {
    const e = this.stats?.envois;
    if (!e) return [];
    return [
      { libelle: 'Réponses envoyées',                 nombre: e.envoyes,             gravite: 'ok',        icone: '✔' },
      { libelle: 'En attente de validation',          nombre: e.enAttenteValidation, gravite: e.enAttenteValidation > 0 ? 'attention' : 'neutre', icone: '⏳' },
      { libelle: 'Envoi programmé',                   nombre: e.programmes,          gravite: 'neutre',    icone: '🕒' },
      { libelle: 'Annulées par un agent',             nombre: e.annules,             gravite: 'neutre',    icone: '⊘' },
      { libelle: 'Échecs d\'envoi',                   nombre: e.echecs,              gravite: e.echecs > 0 ? 'erreur' : 'neutre', icone: '⚠' },
      { libelle: 'Non envoyées (client sans adresse)', nombre: e.nonEnvoyes,         gravite: e.nonEnvoyes > 0 ? 'attention' : 'neutre', icone: '✉' },
      { libelle: 'Aucun envoi tenté',                 nombre: e.sansEnvoi,           gravite: 'neutre',    icone: '–' },
    ];
  }
}
