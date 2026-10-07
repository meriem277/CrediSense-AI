import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';

import { Statistiques, pasRond, HAUTEUR, LARGEUR } from './statistiques';
import { StatistiquesDonnees, PointEvolution } from '../../../models/statistiques.model';

const BASE = 260 - 28;   // ordonnée de la ligne de base du graphique (HAUTEUR - marge basse)

function point(date: string, eligible = 0, conditionnel = 0, refus = 0, aCompleter = 0): PointEvolution {
  return { date, eligible, conditionnel, refus, aCompleter, total: eligible + conditionnel + refus + aCompleter };
}

function jours(n: number, surcharge: Record<number, PointEvolution> = {}): PointEvolution[] {
  return Array.from({ length: n }, (_, i) => surcharge[i] ?? point(`2026-10-${String(i + 1).padStart(2, '0')}`));
}

const STATS: StatistiquesDonnees = {
  periode: { du: '2026-10-01', au: '2026-10-10', jours: 10 },
  dossiersDeposes: 12,
  decisionsTotal: 9,
  parDecision: { ELIGIBLE: 3, CONDITIONNEL: 1, REFUS: 2, A_COMPLETER: 2, INDETERMINE: 1 },
  decisionsDefinitives: 6,
  tauxAcceptation: 0.5,
  tauxAcceptationAvecConditions: 2 / 3,
  tauxRefus: 1 / 3,
  delaiTraitement: { moyenneHeures: 6, medianeHeures: 4, maxHeures: 12, echantillon: 3 },
  delaiEnvoi: { moyenneHeures: null, medianeHeures: null, maxHeures: null, echantillon: 0 },
  montantMoyenDemande: 2000,
  motifs: [
    { decision: 'REFUS', motif: 'Taux d\'endettement', nombre: 2 },
    { decision: 'REFUS', motif: 'Plafond du montant', nombre: 1 },
    { decision: 'A_COMPLETER', motif: 'Information manquante : Dettes existantes', nombre: 1 },
  ],
  granularite: 'JOUR',
  evolution: jours(10, {
    2: point('2026-10-03', 2, 0, 1, 0),
    4: point('2026-10-05', 0, 1, 0, 1),
  }),
  envois: { envoyes: 4, echecs: 1, nonEnvoyes: 0, enAttenteValidation: 2, programmes: 0, annules: 1, sansEnvoi: 1,
            enAttenteValidationTotal: 3 },
};

/** Boîte englobante d'un tracé « M x,y L x,y Q x,y x,y … Z ». */
function bornes(chemin: string) {
  const paires = [...chemin.matchAll(/(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/g)].map(m => [Number(m[1]), Number(m[2])]);
  const xs = paires.map(p => p[0]);
  const ys = paires.map(p => p[1]);
  return { xmin: Math.min(...xs), xmax: Math.max(...xs), ymin: Math.min(...ys), ymax: Math.max(...ys) };
}

function iso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

describe('Statistiques — tableau de bord', () => {
  let fixture: ComponentFixture<Statistiques>;
  let composant: Statistiques;
  let http: HttpTestingController;

  const requeteStats = () => http.expectOne(r => r.url.endsWith('/api/admin/statistiques'));
  const el = () => fixture.nativeElement as HTMLElement;
  /** Texte d'un élément, une espace entre chaque morceau (le DOM n'en met pas entre deux éléments). */
  const texteDe = (racine: Node) => {
    const morceaux: string[] = [];
    const parcours = document.createTreeWalker(racine, NodeFilter.SHOW_TEXT);
    while (parcours.nextNode()) {
      const t = parcours.currentNode.textContent!.trim();
      if (t) morceaux.push(t);
    }
    return morceaux.join(' ').replace(/\s+/g, ' ');
  };
  const texte = () => texteDe(el());
  const requete = (s: string) => el().querySelector(s) as HTMLElement | null;
  const toutes = (s: string) => Array.from(el().querySelectorAll(s)) as HTMLElement[];

  /** Répond à la requête de statistiques en cours et rafraîchit l'écran. */
  function charger(donnees: StatistiquesDonnees = STATS) {
    requeteStats().flush(donnees);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Statistiques],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(Statistiques);
    composant = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();                    // ngOnInit : demande les 30 derniers jours
  });

  // Même si une vérification échoue, le module de test est remis à zéro (sinon les tests suivants plantent en cascade)
  afterEach(() => {
    try { http.verify(); } finally { TestBed.resetTestingModule(); }
  });

  // ── Période ────────────────────────────────────────────────────────────────

  it('à l\'ouverture : les 30 derniers jours, aujourd\'hui compris', () => {
    const r = requeteStats();
    const aujourdhui = new Date();
    const debut = new Date();
    debut.setDate(aujourdhui.getDate() - 29);

    expect(r.request.params.get('au')).toBe(iso(aujourdhui));
    expect(r.request.params.get('du')).toBe(iso(debut));
    expect(composant.preset).toBe('30j');
    r.flush(STATS);
  });

  it('un autre préréglage redemande la période correspondante', () => {
    charger();
    composant.appliquerPreset('7j');
    const r = requeteStats();
    const debut = new Date();
    debut.setDate(debut.getDate() - 6);

    expect(r.request.params.get('du')).toBe(iso(debut));
    expect(composant.preset).toBe('7j');
    r.flush(STATS);
    fixture.detectChanges();
    expect(requete('.preset.actif')!.textContent).toContain('7 jours');
  });

  it('un préréglage inconnu ne fait rien', () => {
    charger();
    composant.appliquerPreset('inconnu');

    http.expectNone(r => r.url.endsWith('/api/admin/statistiques'));
  });

  it('période personnalisée valide : la requête part avec les deux dates', () => {
    charger();
    composant.du = '2026-09-01';
    composant.au = '2026-09-30';
    composant.appliquerPersonnalise();

    const r = requeteStats();
    expect(r.request.params.get('du')).toBe('2026-09-01');
    expect(r.request.params.get('au')).toBe('2026-09-30');
    expect(composant.preset).toBe('perso');
    r.flush(STATS);
  });

  it('période incohérente : message clair, aucune requête', () => {
    charger();
    const cas: [string, string, string][] = [
      ['', '2026-09-30', 'Renseignez les deux dates'],
      ['2026-10-05', '2026-10-01', 'après la date de fin'],
      ['2020-01-01', '2026-10-01', 'ne peut pas dépasser 800 jours'],
    ];
    for (const [du, au, attendu] of cas) {
      composant.du = du;
      composant.au = au;
      composant.appliquerPersonnalise();
      fixture.detectChanges();

      expect(composant.erreur).toContain(attendu);
      expect(requete('.alerte')!.textContent).toContain(attendu);
    }
    http.expectNone(r => r.url.endsWith('/api/admin/statistiques'));
  });

  // ── Chiffres clés ──────────────────────────────────────────────────────────

  it('les tuiles montrent les volumes, le taux d\'acceptation et les délais', () => {
    charger();

    expect(texte()).toContain('Dossiers déposés 12');
    expect(texte()).toContain('Décisions rendues 9 dont 6 définitives');
    expect(texte()).toContain('Taux d\'acceptation 50 %');
    expect(texte()).toContain('avec conditions : 66,7 %');
    expect(texte()).toContain('Délai moyen de traitement 6 h');
    expect(texte()).toContain('médiane 4 h · max 12 h');
  });

  it('sans décision définitive : un tiret et une explication, jamais 0 %', () => {
    charger({ ...STATS, tauxAcceptation: null, tauxAcceptationAvecConditions: null, decisionsDefinitives: 0 });

    expect(texte()).toContain('Taux d\'acceptation —');
    expect(texte()).toContain('aucune décision définitive');
    expect(texte()).not.toContain('0 %');
  });

  it('des réponses attendent un agent : la tuile le signale avec une icône et un texte', () => {
    charger();

    const tuile = requete('.tuile-attention')!;
    expect(tuile).toBeTruthy();
    expect(tuile.textContent).toContain('Réponses à valider');
    expect(tuile.textContent).toContain('3');
    expect(tuile.textContent).toContain('en attente d\'un agent');
    expect(tuile.querySelector('.icone-etat')).toBeTruthy();
  });

  it('rien en attente : la tuile reste neutre', () => {
    charger({ ...STATS, envois: { ...STATS.envois, enAttenteValidationTotal: 0 } });

    expect(requete('.tuile-attention')).toBeNull();
    expect(texte()).toContain('rien en attente');
  });

  it('formats : pourcentage, délai, montant', () => {
    charger();
    expect(composant.pourcent(0.333)).toBe('33,3 %');
    expect(composant.pourcent(null)).toBe('—');
    expect(composant.delai(0.2)).toBe('12 min');
    expect(composant.delai(3.5)).toBe('3,5 h');
    expect(composant.delai(72)).toBe('3 j');
    expect(composant.delai(null)).toBe('—');
    expect(composant.montant(null)).toBe('—');
    expect(composant.montant(1234.4).replace(/\s/g, ' ')).toBe('1 234 DT');
  });

  // ── État vide ──────────────────────────────────────────────────────────────

  it('aucune décision sur la période : message, et aucun graphique vide', () => {
    charger({ ...STATS, decisionsTotal: 0, decisionsDefinitives: 0, parDecision: {}, evolution: jours(10), motifs: [] });

    expect(texte()).toContain('Aucune décision rendue sur cette période');
    expect(requete('svg')).toBeNull();
    expect(requete('.barres')).toBeNull();
  });

  // ── Répartition ────────────────────────────────────────────────────────────

  it('répartition : une barre par décision, proportionnelle, avec son nombre et sa part', () => {
    charger();

    const lignes = toutes('.barres')[0].querySelectorAll('.barre-ligne');
    expect(lignes.length).toBe(5);
    const eligibles = lignes[0] as HTMLElement;
    expect(eligibles.textContent).toContain('Éligible');
    expect(eligibles.textContent).toContain('3');
    expect(eligibles.textContent).toContain('33,3 %');                       // 3 sur 9
    expect((eligibles.querySelector('.barre') as HTMLElement).style.width).toBe('100%');   // la plus grande
    const refus = lignes[2] as HTMLElement;
    expect(parseFloat((refus.querySelector('.barre') as HTMLElement).style.width)).toBeCloseTo(66.67, 1);
  });

  it('une décision à zéro n\'a pas de barre, mais garde sa ligne et son libellé', () => {
    charger({ ...STATS, parDecision: { ELIGIBLE: 4, CONDITIONNEL: 0, REFUS: 0, A_COMPLETER: 0, INDETERMINE: 0 }, decisionsTotal: 4 });

    const ligne = toutes('.barres')[0].querySelectorAll('.barre-ligne')[1] as HTMLElement;
    expect(ligne.textContent).toContain('Conditionnel');
    expect(ligne.querySelector('.barre')).toBeNull();
  });

  // ── Graphique d'évolution : géométrie ──────────────────────────────────────

  it('échelle ronde : des pas de 1, 2, 5, 10, 20, 50…', () => {
    charger();
    expect(pasRond(0)).toBe(1);
    expect(pasRond(3)).toBe(1);
    expect(pasRond(7)).toBe(2);
    expect(pasRond(10)).toBe(5);
    expect(pasRond(100)).toBe(50);
    expect(pasRond(1000)).toBe(500);
  });

  it('le sommet de l\'échelle couvre toujours la colonne la plus haute, avec peu de graduations', () => {
    charger();
    for (const total of [0, 1, 3, 7, 12, 40, 99, 250]) {
      composant.stats = { ...STATS, evolution: jours(10, { 0: point('2026-10-01', total) }) };
      expect(composant.sommet).toBeGreaterThanOrEqual(total);
      expect(composant.graduations.length).toBeLessThanOrEqual(6);
      expect(composant.graduations[0].valeur).toBe(0);
    }
  });

  it('une colonne par jour, chacune avec une zone de survol focalisable et une description', () => {
    charger();

    expect(composant.colonnes.length).toBe(10);
    const zones = toutes('rect.zone');
    expect(zones.length).toBe(10);
    zones.forEach(z => expect(z.getAttribute('tabindex')).toBe('0'));
    expect(zones[2].getAttribute('aria-label')).toContain('3 octobre');
    expect(zones[2].getAttribute('aria-label')).toContain('2 éligible(s)');
    expect(zones[2].getAttribute('aria-label')).toContain('1 refus');
    expect(zones[2].getAttribute('aria-label')).toContain('total 3');
  });

  it('la zone de survol est bien plus large que la colonne', () => {
    charger();
    const c = composant.colonnes[2];
    const largeurColonne = bornes(c.segments[0].chemin).xmax - bornes(c.segments[0].chemin).xmin;

    expect(c.largeurCase).toBeGreaterThan(largeurColonne);
    expect(c.largeurCase).toBeGreaterThanOrEqual(24);
  });

  it('colonnes fines (24 px au plus) et segments empilés dans l\'ordre éligible, conditionnel, refus, à compléter', () => {
    charger({ ...STATS, evolution: jours(10, { 3: point('2026-10-04', 1, 1, 1, 1) }) });

    const c = composant.colonnes[3];
    expect(c.segments.map(s => s.cle)).toEqual(['ELIGIBLE', 'CONDITIONNEL', 'REFUS', 'A_COMPLETER']);
    for (const s of c.segments) {
      const b = bornes(s.chemin);
      expect(b.xmax - b.xmin).toBeLessThanOrEqual(24 + 1e-6);
    }
  });

  it('le segment du bas touche la ligne de base, les suivants laissent 2 px de surface', () => {
    charger({ ...STATS, evolution: jours(10, { 3: point('2026-10-04', 1, 1, 1, 0) }) });

    const [bas, milieu, haut] = composant.colonnes[3].segments.map(s => bornes(s.chemin));
    expect(bas.ymax).toBeCloseTo(BASE, 1);                 // posé sur la base
    expect(milieu.ymax).toBeCloseTo(bas.ymin - 2, 1);      // 2 px d'écart avec le segment du dessous
    expect(haut.ymax).toBeCloseTo(milieu.ymin - 2, 1);
  });

  it('hauteur proportionnelle à la valeur', () => {
    charger({ ...STATS, evolution: jours(10, { 1: point('2026-10-02', 2), 2: point('2026-10-03', 4) }) });

    const deux = bornes(composant.colonnes[1].segments[0].chemin);
    const quatre = bornes(composant.colonnes[2].segments[0].chemin);
    expect((quatre.ymax - quatre.ymin) / (deux.ymax - deux.ymin)).toBeCloseTo(2, 1);
  });

  it('seul le bout de donnée est arrondi : le haut de la pile, jamais la base', () => {
    charger({ ...STATS, evolution: jours(10, { 3: point('2026-10-04', 2, 0, 2, 0), 5: point('2026-10-06', 3) }) });

    const [bas, haut] = composant.colonnes[3].segments;
    expect(bas.chemin).not.toContain('Q');                 // base carrée, pas d'arrondi au milieu de la pile
    expect(haut.chemin).toContain('Q');                    // bout arrondi
    // une colonne à un seul segment : arrondie en haut, carrée en bas
    const seule = composant.colonnes[5].segments[0];
    expect(seule.chemin).toContain('Q');
    expect(bornes(seule.chemin).ymax).toBeCloseTo(BASE, 1);
  });

  it('un jour sans décision ne trace rien', () => {
    charger();

    expect(composant.colonnes[0].segments).toEqual([]);
    expect(composant.colonnes[0].point.total).toBe(0);
  });

  it('les étiquettes de l\'axe sont espacées pour ne pas se chevaucher', () => {
    charger({ ...STATS, evolution: jours(30), periode: { du: '2026-10-01', au: '2026-10-30', jours: 30 } });

    const etiquettes = composant.colonnes.filter(c => c.etiquette);
    expect(etiquettes.length).toBeLessThanOrEqual(10);
    expect(etiquettes[0].etiquette).toBe('01/10');
    expect(toutes('text.axe').length).toBeGreaterThan(etiquettes.length);   // + les graduations de l'axe Y
  });

  it('regroupement par semaine : libellés « sem. », légende et tableau en conséquence', () => {
    charger({ ...STATS, granularite: 'SEMAINE',
              evolution: [point('2026-07-06', 1), point('2026-07-13', 0, 0, 2), point('2026-07-20')] });

    expect(composant.colonnes[0].etiquette).toBe('sem. 06/07');
    expect(composant.colonnes[0].libelleLong).toContain('semaine du 6 juillet');
    expect(texte()).toContain('Décisions par semaine');
    expect(texte()).toContain('Semaine du');
  });

  // ── Légende, infobulle, vue en tableau ─────────────────────────────────────

  it('la légende nomme les quatre décisions avec leur total sur la période', () => {
    charger();

    const legende = toutes('.legende li').map(li => li.textContent!.replace(/\s+/g, ' ').trim());
    expect(legende).toEqual(['Éligible 3', 'Conditionnel 1', 'Refus 2', 'À compléter 2']);
  });

  it('le graphique est décrit pour un lecteur d\'écran et renvoie au tableau', () => {
    charger();

    const svg = requete('svg')!;
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toContain('10 jours');
    expect(svg.getAttribute('aria-label')).toContain('9 décisions');
    expect(svg.getAttribute('aria-label')).toContain('tableau de données');
  });

  it('survoler une colonne affiche une infobulle avec TOUTES les décisions, la valeur en premier', () => {
    charger();
    const zone = toutes('rect.zone')[2];

    zone.dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();

    const bulle = requete('.infobulle')!;
    expect(bulle).toBeTruthy();
    expect(bulle.textContent).toContain('3 octobre');
    const lignes = Array.from(bulle.querySelectorAll('.infobulle-ligne')).map(l => texteDe(l));
    expect(lignes).toEqual(['2 Éligible', '0 Conditionnel', '1 Refus', '0 À compléter']);
    expect(bulle.querySelector('.infobulle-total')!.textContent).toContain('3');
    expect(bulle.querySelector('.cle')).toBeTruthy();                     // un trait de couleur, pas une boîte

    zone.dispatchEvent(new Event('pointerleave'));
    fixture.detectChanges();
    expect(requete('.infobulle')).toBeNull();
  });

  it('au clavier : le focus montre la même infobulle que le survol', () => {
    charger();
    const zone = toutes('rect.zone')[4];

    zone.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(requete('.infobulle')!.textContent).toContain('5 octobre');

    zone.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
    expect(requete('.infobulle')).toBeNull();
  });

  it('l\'infobulle passe à gauche de la colonne quand celle-ci est au bord droit', () => {
    charger();

    toutes('rect.zone')[0].dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();
    expect(requete('.infobulle')!.classList.contains('a-droite')).toBe(false);

    toutes('rect.zone')[9].dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();
    expect(requete('.infobulle')!.classList.contains('a-droite')).toBe(true);
  });

  it('la colonne survolée est marquée active', () => {
    charger();
    toutes('rect.zone')[2].dispatchEvent(new Event('pointerenter'));
    fixture.detectChanges();

    expect(toutes('g.colonne.active').length).toBe(1);
  });

  it('chaque graphique a sa vue en tableau avec toutes les valeurs', () => {
    charger();

    const tableaux = toutes('details.vue-tableau');
    expect(tableaux.length).toBe(2);                                       // évolution et motifs
    const evolution = tableaux[0].querySelectorAll('tbody tr');
    expect(evolution.length).toBe(10);
    expect(texteDe(evolution[2])).toBe('03/10/2026 2 0 1 0 3');
    expect(tableaux[0].querySelectorAll('thead th').length).toBe(6);       // jour + 4 décisions + total
  });

  // ── Motifs ─────────────────────────────────────────────────────────────────

  it('les motifs sont classés avec leur décision écrite en toutes lettres', () => {
    charger();

    const lignes = toutes('.barre-ligne.motif');
    expect(lignes.length).toBe(3);
    expect(lignes[0].textContent).toContain('Taux d\'endettement');
    expect(lignes[0].textContent).toContain('Refus');
    expect(lignes[0].textContent).toContain('2');
    expect(lignes[2].textContent).toContain('À compléter');
  });

  it('au plus 8 motifs sont dessinés, le tableau donne tous les autres', () => {
    const motifs = Array.from({ length: 12 }, (_, i) => ({ decision: 'REFUS', motif: `Motif ${i}`, nombre: 12 - i }));
    charger({ ...STATS, motifs });

    expect(toutes('.barre-ligne.motif').length).toBe(8);
    const tableau = toutes('details.vue-tableau')[1];
    expect(tableau.querySelectorAll('tbody tr').length).toBe(12);
    expect(tableau.querySelector('summary')!.textContent).toContain('12');
  });

  it('aucun motif : message, pas de graphique ni de tableau', () => {
    charger({ ...STATS, motifs: [] });

    expect(texte()).toContain('Aucun motif à signaler');
    expect(toutes('.barre-ligne.motif').length).toBe(0);
    expect(toutes('details.vue-tableau').length).toBe(1);                  // seulement celui de l'évolution
  });

  // ── Envois ─────────────────────────────────────────────────────────────────

  it('envois : chaque état a une icône, un libellé et un nombre, la couleur ne porte rien seule', () => {
    charger();

    const lignes = toutes('.envois li');
    expect(lignes.length).toBe(7);
    lignes.forEach(l => {
      expect(l.querySelector('.icone-etat')!.textContent!.trim()).not.toBe('');
      expect(l.querySelector('.envoi-libelle')!.textContent!.trim()).not.toBe('');
    });
    const echecs = lignes.find(l => l.textContent!.includes('Échecs d\'envoi'))!;
    expect(echecs.classList.contains('envoi-erreur')).toBe(true);
    expect(echecs.textContent).toContain('1');
    const attente = lignes.find(l => l.textContent!.includes('En attente de validation'))!;
    expect(attente.classList.contains('envoi-attention')).toBe(true);
  });

  it('aucun échec ni attente : ces lignes restent neutres', () => {
    charger({ ...STATS, envois: { ...STATS.envois, echecs: 0, enAttenteValidation: 0 } });

    const lignes = toutes('.envois li');
    expect(lignes.find(l => l.textContent!.includes('Échecs'))!.classList.contains('envoi-neutre')).toBe(true);
  });

  it('délais : traitement et envoi, avec un tiret quand rien ne s\'est envoyé', () => {
    charger();

    const lignes = toutes('table.mini tbody tr').map(l => l.textContent!.replace(/\s+/g, ' '));
    expect(lignes[0]).toContain('6 h');
    expect(lignes[0]).toContain('12 h');
    expect(lignes[1]).toContain('—');
    expect(texte()).toContain('Montant moyen demandé : 2 000 DT'.replace(' ', ' ').replace(/\s/g, ' '));
  });

  it('les définitions des taux sont affichées', () => {
    charger();

    expect(texte()).toContain('Taux d\'acceptation = éligibles ÷ décisions définitives');
    expect(texte()).toContain('« à compléter » n\'est pas une décision');
  });

  // ── Chargement et erreurs ──────────────────────────────────────────────────

  it('pendant un rechargement, les chiffres précédents restent affichés, estompés', () => {
    charger();
    composant.appliquerPreset('90j');
    fixture.detectChanges();

    expect(requete('.contenu')!.classList.contains('recharge')).toBe(true);
    expect(texte()).toContain('Dossiers déposés 12');                      // pas de squelette ni de page vide
    requeteStats().flush(STATS);
    fixture.detectChanges();
    expect(requete('.contenu')!.classList.contains('recharge')).toBe(false);
  });

  it('première charge : un message d\'attente simple', () => {
    // le chargement initial est déjà parti dans beforeEach
    expect(texte()).toContain('Chargement des statistiques');
    requeteStats().flush(STATS);
  });

  it('erreur du serveur : message clair, et les chiffres déjà affichés restent', () => {
    charger();
    composant.appliquerPreset('7j');
    requeteStats().flush('panne', { status: 500, statusText: 'Erreur serveur' });
    fixture.detectChanges();

    expect(requete('.alerte')!.textContent).toContain('n\'ont pas pu être chargées');
    expect(texte()).toContain('Dossiers déposés 12');
    expect(composant.chargement).toBe(false);
  });

  // ── Export Excel ───────────────────────────────────────────────────────────

  describe('export Excel', () => {
    let enregistrer: ReturnType<typeof vi.fn>;
    const requeteExport = () => http.expectOne(r => r.url.endsWith('/api/admin/statistiques/export.xlsx'));

    beforeEach(() => {
      charger();
      enregistrer = vi.fn();
      (composant as any).enregistrer = enregistrer;
    });

    it('demande le fichier pour la période affichée et l\'enregistre sous un nom explicite', () => {
      composant.du = '2026-10-01';
      composant.au = '2026-10-10';
      composant.exporter();

      const r = requeteExport();
      expect(r.request.params.get('du')).toBe('2026-10-01');
      expect(r.request.params.get('au')).toBe('2026-10-10');
      expect(r.request.responseType).toBe('blob');
      r.flush(new Blob(['PK'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));

      expect(enregistrer).toHaveBeenCalledTimes(1);
      expect(enregistrer.mock.calls[0][1]).toBe('Statistiques-credisense-2026-10-01_2026-10-10.xlsx');
      expect(composant.exportEnCours).toBe(false);
    });

    it('un seul export à la fois', () => {
      composant.exporter();
      composant.exporter();

      requeteExport().flush(new Blob(['PK']));          // une seule requête est partie
      expect(enregistrer).toHaveBeenCalledTimes(1);
    });

    it('le bouton est bloqué pendant la génération', () => {
      composant.exporter();
      fixture.detectChanges();

      const bouton = requete('button.export') as HTMLButtonElement;
      expect(bouton.disabled).toBe(true);
      expect(bouton.textContent).toContain('Génération');
      requeteExport().flush(new Blob(['PK']));
    });

    it('échec de l\'export : message clair, on peut réessayer', () => {
      composant.exporter();
      requeteExport().error(new ProgressEvent('error'), { status: 500, statusText: 'Erreur serveur' });
      fixture.detectChanges();

      expect(requete('.alerte')!.textContent).toContain('export Excel n\'a pas pu être généré');
      expect(composant.exportEnCours).toBe(false);
      composant.exporter();
      expect(composant.erreurExport).toBe('');
      requeteExport().flush(new Blob(['PK']));
    });

    it('période saisie incohérente : rien n\'est exporté', () => {
      composant.du = '2026-12-01';
      composant.au = '2026-10-01';
      composant.exporter();

      http.expectNone(r => r.url.includes('export.xlsx'));
    });
  });

  // ── Accessibilité des filtres ──────────────────────────────────────────────

  it('les préréglages forment un groupe de boutons radio avec l\'état coché', () => {
    charger();

    const boutons = toutes('.presets button');
    expect(boutons.length).toBe(4);
    expect(boutons.every(b => b.getAttribute('role') === 'radio')).toBe(true);
    expect(boutons.filter(b => b.getAttribute('aria-checked') === 'true').length).toBe(1);
    expect(boutons.find(b => b.getAttribute('aria-checked') === 'true')!.textContent).toContain('30 jours');
  });

  it('constantes du graphique cohérentes', () => {
    charger();
    expect(LARGEUR).toBe(640);
    expect(HAUTEUR).toBe(260);
  });
});
