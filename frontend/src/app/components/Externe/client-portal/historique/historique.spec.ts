import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';

import { Historique } from './historique';
import { jwtInterceptor } from '../../../../services/jwt.interceptor';
import { AuthService } from '../../../../services/Interne/auth.service';
import { ClientAuthService } from '../../../../services/Externe/Client-auth.service';
import { environment } from '../../../../../environments/environment';

const DOSSIERS = [
  { dossierId: '1c33d885-0000-0000-0000-000000000001', typeCredit: 'CONSOMMATION', statut: 'APPROUVE',   createdAt: '2026-10-06T20:34:22.997021' },
  { dossierId: 'f1cc090a-0000-0000-0000-000000000002', typeCredit: 'CONSOMMATION', statut: 'EN_COURS',   createdAt: '2026-10-07T02:45:14.788404' },
  { dossierId: 'a39df9a6-0000-0000-0000-000000000003', typeCredit: 'CONSOMMATION', statut: 'EN_ATTENTE', createdAt: '2026-10-07T03:24:01.438838' },
  { dossierId: 'f0c73fb7-0000-0000-0000-000000000004', typeCredit: 'IMMOBILIER',   statut: 'REFUSE',     createdAt: '2026-10-07T23:51:53.473737' },
];

describe('Historique — page « Mes demandes »', () => {
  let fixture: ComponentFixture<Historique>;
  let http: HttpTestingController;
  let connecte: boolean;
  let deconnexions: number;

  const composant = () => fixture.componentInstance;
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const tous = (s: string) => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(s)) as HTMLElement[];
  const un = (s: string) => (fixture.nativeElement as HTMLElement).querySelector(s) as HTMLElement | null;

  const demarrer = (dossiers: unknown[] | 'erreur' = DOSSIERS) => {
    fixture = TestBed.createComponent(Historique);
    fixture.detectChanges();
    const requete = http.expectOne(r => r.url.startsWith(`${environment.apiUrl}/api/clients/historique`));
    if (dossiers === 'erreur') requete.flush('boom', { status: 500, statusText: 'Server Error' });
    else requete.flush(dossiers);
    fixture.detectChanges();
  };

  beforeEach(async () => {
    connecte = true;
    deconnexions = 0;
    await TestBed.configureTestingModule({
      imports: [Historique],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: ClientAuthService, useValue: {
            isLoggedIn: () => connecte,
            getUser: () => ({ prenom: 'Meriem', nom: 'Rehouma', email: 'meriem@exemple.tn' }),
            logout: () => { deconnexions++; } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  // ── Accès et chargement ──────────────────────────────────────────────────
  it('renvoie vers la connexion quand le client n\'est pas connecté', () => {
    connecte = false;
    const navigation = TestBed.inject(Router);
    let destination = '';
    navigation.navigate = (commandes: any[]) => { destination = commandes[0]; return Promise.resolve(true); };
    fixture = TestBed.createComponent(Historique);
    fixture.detectChanges();
    expect(destination).toBe('/client/login');
    http.expectNone(r => r.url.includes('/historique'));
  });

  it('charge l\'historique avec l\'adresse e-mail encodée', () => {
    fixture = TestBed.createComponent(Historique);
    fixture.detectChanges();
    const requete = http.expectOne(r => r.url.includes('/api/clients/historique'));
    expect(requete.request.url).toContain('email=meriem%40exemple.tn');
    requete.flush([]);
  });

  it('affiche le nom et les initiales du client', () => {
    demarrer();
    expect(texte()).toContain('Meriem Rehouma');
    expect(un('.client-avatar')!.textContent).toContain('MR');
  });

  it('affiche un message d\'erreur quand le chargement échoue', () => {
    demarrer('erreur');
    expect(un('.alert-error')!.textContent).toContain("Erreur lors du chargement");
    expect(tous('.dossier').length).toBe(0);
  });

  it('propose une première demande quand il n\'y en a aucune', () => {
    demarrer([]);
    expect(un('.hist-empty')).not.toBeNull();
    expect(texte()).toContain('Aucune demande pour le moment');
    expect(un('.stats-row')).toBeNull();
  });

  // ── Compteurs : « en étude » compte l'attente ET le cours ────────────────
  it('compte comme « en étude » les demandes en attente et en cours', () => {
    demarrer();
    expect(composant().totalDossiers).toBe(4);
    expect(composant().enEtude).toBe(2);
    expect(composant().approuves).toBe(1);
    expect(composant().refuses).toBe(1);
    const tuiles = tous('.stat-card').map(t => [t.querySelector('.stat-label')!.textContent!.trim(), t.querySelector('.stat-value')!.textContent!.trim()]);
    expect(tuiles).toEqual([['Total des demandes', '4'], ['En étude', '2'], ['Approuvées', '1'], ['Refusées', '1']]);
  });

  // ── Dernière demande ─────────────────────────────────────────────────────
  it('met en avant la demande la plus récente', () => {
    demarrer();
    expect(composant().dernierDossier.dossierId).toContain('f0c73fb7');
    const hero = un('.hero')!;
    expect(hero.textContent).toContain('Votre dernière demande');
    expect(hero.textContent).toContain('Crédit immobilier');
    expect(hero.textContent).toContain('F0C73FB7');
    expect(hero.className).toContain('statut-refuse');
  });

  it('dessine une frise de trois étapes et marque l\'étape atteinte', () => {
    demarrer();
    expect(tous('.frise li').length).toBe(3);
    expect(composant().etapeActive('EN_ATTENTE')).toBe(1);
    expect(composant().etapeActive('EN_COURS')).toBe(2);
    expect(composant().etapeActive('APPROUVE')).toBe(3);
    expect(composant().etapeActive('REFUSE')).toBe(3);
    expect(composant().etapeActive('INCONNU')).toBe(0);
    expect(tous('.frise li.courante').length).toBe(1);
    expect(tous('.frise li.faite').length).toBe(2);                    // décision rendue : les deux premières sont faites
  });

  it('explique chaque statut en une phrase claire, sans promesse', () => {
    demarrer();
    const c = composant();
    expect(c.messageStatut('EN_ATTENTE')).toContain('bien reçu');
    expect(c.messageStatut('EN_COURS')).toContain('étudie');
    expect(c.messageStatut('APPROUVE')).toContain('approuvée');
    expect(c.messageStatut('REFUSE')).toContain("n'a pas pu être acceptée");
    expect(c.messageStatut('X')).toBe('');
  });

  // ── Liste, filtres, recherche, tri ───────────────────────────────────────
  it('liste toutes les demandes, la plus récente d\'abord', () => {
    demarrer();
    const refs = tous('.dossier-meta').map(m => m.textContent!.trim().slice(0, 11));
    expect(refs).toEqual(['N° F0C73FB7', 'N° A39DF9A6', 'N° F1CC090A', 'N° 1C33D885']);
  });

  it('filtre par statut', () => {
    demarrer();
    composant().choisirFiltre('etude');
    fixture.detectChanges();
    expect(tous('.dossier').length).toBe(2);
    composant().choisirFiltre('approuve');
    fixture.detectChanges();
    expect(tous('.dossier').length).toBe(1);
    composant().choisirFiltre('refuse');
    fixture.detectChanges();
    expect(tous('.dossier').length).toBe(1);
    expect(composant().compteurFiltre('etude')).toBe(2);
  });

  it('retrouve une demande par son numéro, avec ou sans le préfixe « N° »', () => {
    demarrer();
    composant().recherche = 'a39df9a6';
    expect(composant().dossiersAffiches.length).toBe(1);
    composant().recherche = 'N° A39DF9';
    expect(composant().dossiersAffiches.length).toBe(1);
    composant().recherche = 'zzz';
    fixture.detectChanges();
    expect(composant().dossiersAffiches.length).toBe(0);
    expect(un('.aucun-resultat')).not.toBeNull();
  });

  it('réaffiche tout après une recherche sans résultat', () => {
    demarrer();
    composant().recherche = 'zzz';
    composant().choisirFiltre('refuse');
    composant().reinitialiserFiltres();
    expect(composant().filtre).toBe('tous');
    expect(composant().recherche).toBe('');
    expect(composant().dossiersAffiches.length).toBe(4);
  });

  it('trie par date, dans les deux sens', () => {
    demarrer();
    composant().tri = 'ancien';
    expect(composant().dossiersAffiches[0].dossierId).toContain('1c33d885');
    composant().tri = 'recent';
    expect(composant().dossiersAffiches[0].dossierId).toContain('f0c73fb7');
  });

  it('ne modifie pas l\'ordre d\'origine des données en filtrant', () => {
    demarrer();
    const avant = composant().dossiers.map(d => d.dossierId);
    composant().tri = 'ancien';
    composant().dossiersAffiches;
    expect(composant().dossiers.map(d => d.dossierId)).toEqual(avant);
  });

  // ── Présentation lisible ─────────────────────────────────────────────────
  it('écrit le type de crédit en toutes lettres', () => {
    demarrer();
    const c = composant();
    expect(c.libelleType('CONSOMMATION')).toBe('Crédit à la consommation');
    expect(c.libelleType('immobilier')).toBe('Crédit immobilier');
    expect(c.libelleType('AUTO')).toBe('Crédit Auto');
    expect(c.libelleType('')).toBe('Demande de crédit');
    expect(c.libelleType(null)).toBe('Demande de crédit');
  });

  it('affiche une date lisible, jamais les microsecondes de la base', () => {
    demarrer();
    const date = composant().formatDate('2026-10-06T20:34:22.997021');
    expect(date).toContain('6 octobre 2026');
    expect(date).toContain('20:34');
    expect(date).not.toContain('997021');
    expect(texte()).not.toContain('997021');
  });

  it('tolère une date absente ou illisible', () => {
    demarrer();
    expect(composant().formatDate('')).toBe('');
    expect(composant().formatDate('pas une date')).toBe('');
    expect(composant().formatDateLongue('pas une date')).toBe('');
  });

  it('range les demandes sans date en dernier plutôt que de planter', () => {
    demarrer([{ dossierId: 'aaaaaaaa-1', statut: 'EN_COURS', createdAt: '' },
              { dossierId: 'bbbbbbbb-2', statut: 'EN_COURS', createdAt: '2026-10-07T10:00:00' }]);
    expect(composant().dossiersAffiches[0].dossierId).toContain('bbbbbbbb');
  });

  it('utilise des libellés de statut adaptés à une demande', () => {
    demarrer();
    const c = composant();
    expect(c.getStatutLabel('EN_ATTENTE')).toBe('Reçue');
    expect(c.getStatutLabel('EN_COURS')).toBe('En étude');
    expect(c.getStatutLabel('APPROUVE')).toBe('Approuvée');
    expect(c.getStatutLabel('REFUSE')).toBe('Refusée');
    expect(c.getStatutLabel('AUTRE')).toBe('AUTRE');
  });

  // ── Détails d'un dossier ─────────────────────────────────────────────────
  it('ouvre le détail, charge les documents et les nomme clairement', () => {
    demarrer();
    composant().voirDetails(DOSSIERS[0]);
    fixture.detectChanges();
    const requete = http.expectOne(`${environment.apiUrl}/api/public/demande/${DOSSIERS[0].dossierId}/fichiers`);
    requete.flush([{ id: 'f1', typeDocument: 'FICHE_PAIE', nomFichier: 'paie-septembre.pdf' },
                   { id: 'f2', typeDocument: 'CIN', nomFichier: 'cin.pdf' }]);
    fixture.detectChanges();

    const modal = un('.modal-box')!;
    expect(modal.textContent).toContain('Crédit à la consommation');
    expect(modal.textContent).toContain('Fiche de paie');
    expect(modal.textContent).toContain("Carte d'identité nationale");
    expect(modal.textContent).toContain('paie-septembre.pdf');
    expect(tous('.btn-voir').length).toBe(2);
    expect(tous('.btn-voir')[0].getAttribute('href')).toContain('/api/public/fichiers/f1/download');
  });

  it('signale l\'absence de documents', () => {
    demarrer();
    composant().voirDetails(DOSSIERS[0]);
    http.expectOne(r => r.url.includes('/fichiers')).flush([]);
    fixture.detectChanges();
    expect(un('.modal-empty')!.textContent).toContain('Aucun fichier trouvé');
  });

  it('ne reste pas bloqué en chargement si la liste des documents échoue', () => {
    demarrer();
    composant().voirDetails(DOSSIERS[0]);
    http.expectOne(r => r.url.includes('/fichiers')).flush('x', { status: 500, statusText: 'Erreur' });
    fixture.detectChanges();
    expect(composant().loadingFichiers).toBe(false);
  });

  it('ferme le détail avec la touche Échap et avec le bouton', () => {
    demarrer();
    composant().voirDetails(DOSSIERS[0]);
    http.expectOne(r => r.url.includes('/fichiers')).flush([]);
    fixture.detectChanges();
    expect(un('.modal-box')).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(un('.modal-box')).toBeNull();

    composant().voirDetails(DOSSIERS[1]);
    http.expectOne(r => r.url.includes('/fichiers')).flush([]);
    fixture.detectChanges();
    un('.modal-close')!.click();
    fixture.detectChanges();
    expect(un('.modal-box')).toBeNull();
  });

  it('propose des boutons accessibles (et non du texte cliquable) pour les détails', () => {
    demarrer();
    const boutons = tous('.dossier .btn-details');
    expect(boutons.length).toBe(4);
    expect(boutons[0].tagName).toBe('BUTTON');
    expect(boutons[0].getAttribute('aria-label')).toContain('F0C73FB7');
  });

  it('se déconnecte', () => {
    demarrer();
    un('.btn-logout')!.click();
    expect(deconnexions).toBe(1);
  });
});


describe('Historique — propositions du conseiller', () => {
  let fixture: ComponentFixture<Historique>;
  let http: HttpTestingController;

  const URL_LISTE = `${environment.apiUrl}/api/clients/mes-demandes/propositions`;
  const id = DOSSIERS[1].dossierId;                       // la demande « en cours »
  const urlProposition = `${environment.apiUrl}/api/clients/mes-demandes/${id}/proposition`;

  const OFFRES = [
    { kind: 'MONTANT_REDUIT', label: 'Montant réduit, même durée', amount: 16300, duration: 12, monthlyPayment: 1433.029, dti: 29.85, totalCost: 17196.348, explanation: 'Sur 12 mois, 16 300 DT passe sous 30 %.' },
    { kind: 'DUREE_ALLONGEE', label: 'Même montant, durée allongée', amount: 20000, duration: 18, monthlyPayment: 1201.142, dti: 25.02, totalCost: 21620.549, explanation: 'En allongeant à 18 mois, le montant passe.' },
  ];
  const proposition = (etat: string, choix: number | null = null) =>
    ({ decision: 'CONDITIONNEL', message: 'm', offres: OFFRES, etat, choix, repondueLe: choix !== null || etat === 'REFUSEE' ? '2026-10-08T10:12:00' : null });

  const composant = () => fixture.componentInstance;
  const texte = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const tous = (s: string) => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(s)) as HTMLElement[];
  const un = (s: string) => (fixture.nativeElement as HTMLElement).querySelector(s) as HTMLElement | null;

  const demarrer = (liste: unknown = [{ dossierId: id, etat: 'EN_ATTENTE_REPONSE' }]) => {
    fixture = TestBed.createComponent(Historique);
    fixture.detectChanges();
    http.expectOne(r => r.url.includes('/api/clients/historique')).flush(DOSSIERS);
    const requete = http.expectOne(URL_LISTE);
    if (liste === 'erreur') requete.flush('x', { status: 403, statusText: 'Forbidden' });
    else requete.flush(liste as any);
    fixture.detectChanges();
  };

  const ouvrir = (etat = proposition('EN_ATTENTE_REPONSE')) => {
    composant().voirDetails(DOSSIERS[1]);
    http.expectOne(urlProposition).flush(etat);
    http.expectOne(r => r.url.includes('/fichiers')).flush([]);
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Historique],
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: ClientAuthService, useValue: {
            isLoggedIn: () => true, logout: () => {},
            getUser: () => ({ prenom: 'Meriem', nom: 'Rehouma', email: 'meriem@exemple.tn' }) } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  // ── Liste et bandeau ─────────────────────────────────────────────────────
  it('signale en haut de page qu\'une proposition attend la réponse du client', () => {
    demarrer();
    expect(un('.action-requise')!.textContent).toContain('Une proposition de votre conseiller attend votre réponse');
    expect(composant().demandesAvecActionRequise.length).toBe(1);
  });

  it('marque la demande concernée et propose « Répondre »', () => {
    demarrer();
    const lignes = tous('.dossier');
    const ligne = lignes.find(l => l.textContent!.includes('F1CC090A'))!;
    expect(ligne.querySelector('.pastille-proposition')!.textContent).toContain('Proposition à examiner');
    expect(ligne.querySelector('.btn-details')!.textContent).toContain('Répondre');
    expect(lignes.filter(l => l.querySelector('.pastille-proposition')).length).toBe(1);
  });

  it('n\'affiche aucun bandeau quand il n\'y a pas de proposition', () => {
    demarrer([]);
    expect(un('.action-requise')).toBeNull();
    expect(tous('.pastille-proposition').length).toBe(0);
    expect(tous('.btn-details').every(b => b.textContent!.includes('Voir détails'))).toBe(true);
  });

  it('ne casse pas la page quand les propositions ne peuvent pas être chargées', () => {
    demarrer('erreur');
    expect(un('.action-requise')).toBeNull();
    expect(tous('.dossier').length).toBe(4);
  });

  it('indique l\'état d\'une demande déjà traitée', () => {
    demarrer([{ dossierId: id, etat: 'ACCEPTEE' }, { dossierId: DOSSIERS[2].dossierId, etat: 'REFUSEE' }]);
    expect(un('.action-requise')).toBeNull();
    const pastilles = tous('.pastille-proposition').map(p => p.textContent!.trim());
    expect(pastilles).toContain('Proposition acceptée');
    expect(pastilles).toContain('Proposition refusée');
  });

  it('compte plusieurs propositions en attente', () => {
    demarrer([{ dossierId: id, etat: 'EN_ATTENTE_REPONSE' }, { dossierId: DOSSIERS[2].dossierId, etat: 'EN_ATTENTE_REPONSE' }]);
    expect(un('.action-requise')!.textContent).toContain('2 propositions de votre conseiller attendent votre réponse');
  });

  // ── Fenêtre de la proposition ────────────────────────────────────────────
  it('affiche les options dans la fenêtre de détails', () => {
    demarrer();
    ouvrir();
    expect(tous('.offre-client').length).toBe(2);
    const t = texte();
    expect(t).toContain('Propositions de votre conseiller');
    expect(t).toContain('Montant réduit, même durée');
    expect(t).toContain('Aucune n\'est appliquée sans votre accord');
    expect(t).toMatch(/16\s?300 DT/);
    expect(t).toContain('29,85 %');
  });

  it('ne charge pas de proposition pour une demande qui n\'en a pas', () => {
    demarrer();
    composant().voirDetails(DOSSIERS[0]);
    http.expectNone(`${environment.apiUrl}/api/clients/mes-demandes/${DOSSIERS[0].dossierId}/proposition`);
    http.expectOne(r => r.url.includes('/fichiers')).flush([]);
    fixture.detectChanges();
    expect(un('.proposition')).toBeNull();
  });

  it('refuse d\'accepter tant qu\'aucune option n\'est choisie', () => {
    demarrer();
    ouvrir();
    const accepter = un('.btn-accepter') as HTMLButtonElement;
    expect(accepter.disabled).toBe(true);
    composant().demanderConfirmation('ACCEPTER');
    expect(composant().confirmation).toBeNull();
  });

  it('sélectionne une option au clic et au clavier', () => {
    demarrer();
    ouvrir();
    tous('.offre-client')[1].click();
    fixture.detectChanges();
    expect(composant().offreSelectionnee).toBe(1);
    expect(tous('.offre-client')[1].className).toContain('choisie');
    expect((un('.btn-accepter') as HTMLButtonElement).disabled).toBe(false);
    expect(un('.btn-accepter')!.textContent).toContain("Accepter l'option 2");

    tous('.offre-client')[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(composant().offreSelectionnee).toBe(0);
  });

  it('demande confirmation avant d\'envoyer la réponse, puis permet de revenir', () => {
    demarrer();
    ouvrir();
    composant().choisirOffre(1);
    composant().demanderConfirmation('ACCEPTER');
    fixture.detectChanges();

    expect(un('.confirmation')!.textContent).toContain('option 2');
    expect(un('.confirmation')!.textContent).toMatch(/20\s?000 DT/);
    expect(un('.confirmation')!.textContent).toContain('définitive');
    http.expectNone(r => r.url.includes('/repondre'));              // rien n'est envoyé avant la confirmation

    composant().annulerConfirmation();
    fixture.detectChanges();
    expect(un('.confirmation')).toBeNull();
    expect(un('.btn-accepter')).not.toBeNull();
  });

  it('envoie l\'acceptation de l\'option choisie et affiche le résultat', () => {
    demarrer();
    ouvrir();
    composant().choisirOffre(1);
    composant().demanderConfirmation('ACCEPTER');
    composant().confirmerReponse();

    const requete = http.expectOne(`${urlProposition}/repondre`);
    expect(requete.request.method).toBe('POST');
    expect(requete.request.body).toEqual({ choix: 'ACCEPTER', offre: 1 });
    requete.flush(proposition('ACCEPTEE', 1));
    fixture.detectChanges();

    expect(un('.reponse-donnee')!.textContent).toContain("Vous avez accepté l'option 2");
    expect(un('.reponse-donnee')!.textContent).toContain('transmise à votre conseiller');
    expect(un('.reponse-actions')).toBeNull();                       // plus de boutons : une seule réponse
    expect(composant().propositions[id]).toBe('ACCEPTEE');
    expect(tous('.offre-client')[0].className).toContain('ecartee');
  });

  it('envoie le refus des propositions', () => {
    demarrer();
    ouvrir();
    composant().demanderConfirmation('REFUSER');
    fixture.detectChanges();
    expect(un('.confirmation')!.textContent).toContain('refusez toutes les propositions');
    composant().confirmerReponse();

    const requete = http.expectOne(`${urlProposition}/repondre`);
    expect(requete.request.body).toEqual({ choix: 'REFUSER', offre: null });
    requete.flush(proposition('REFUSEE'));
    fixture.detectChanges();
    expect(un('.reponse-donnee')!.textContent).toContain('Vous avez refusé les propositions');
    expect(composant().propositions[id]).toBe('REFUSEE');
  });

  it('empêche un double envoi pendant que la réponse part', () => {
    demarrer();
    ouvrir();
    composant().demanderConfirmation('REFUSER');
    composant().confirmerReponse();
    composant().confirmerReponse();
    const requetes = http.match(`${urlProposition}/repondre`);
    expect(requetes.length).toBe(1);
  });

  it('affiche le message du serveur en cas d\'erreur et permet de réessayer', () => {
    demarrer();
    ouvrir();
    composant().choisirOffre(0);
    composant().demanderConfirmation('ACCEPTER');
    composant().confirmerReponse();
    http.expectOne(`${urlProposition}/repondre`).flush({ message: 'Choisissez l\'une des propositions proposées.' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(un('.erreur-reponse')!.textContent).toContain('Choisissez l\'une des propositions');
    expect(composant().envoiReponse).toBe(false);
    expect(un('.btn-accepter')).not.toBeNull();
  });

  it('relit l\'état réel quand la réponse existe déjà (409)', () => {
    demarrer();
    ouvrir();
    composant().choisirOffre(0);
    composant().demanderConfirmation('ACCEPTER');
    composant().confirmerReponse();
    http.expectOne(`${urlProposition}/repondre`).flush({ message: 'Vous avez déjà répondu à cette proposition.' }, { status: 409, statusText: 'Conflict' });
    http.expectOne(urlProposition).flush(proposition('ACCEPTEE', 0));
    fixture.detectChanges();

    expect(un('.reponse-donnee')!.textContent).toContain("Vous avez accepté l'option 1");
    expect(un('.erreur-reponse')!.textContent).toContain('déjà répondu');
  });

  it('donne un message clair quand le serveur ne répond pas', () => {
    demarrer();
    ouvrir();
    composant().demanderConfirmation('REFUSER');
    composant().confirmerReponse();
    http.expectOne(`${urlProposition}/repondre`).error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(un('.erreur-reponse')!.textContent).toContain('n\'a pas pu être enregistrée');
  });

  it('remet la sélection à zéro en fermant puis en rouvrant la fenêtre', () => {
    demarrer();
    ouvrir();
    composant().choisirOffre(1);
    composant().demanderConfirmation('ACCEPTER');
    composant().fermerDetails();
    expect(composant().offreSelectionnee).toBeNull();
    expect(composant().confirmation).toBeNull();
    expect(composant().proposition).toBeNull();
  });

  it('une réponse déjà donnée ne peut plus être modifiée', () => {
    demarrer([{ dossierId: id, etat: 'REFUSEE' }]);
    ouvrir(proposition('REFUSEE'));
    composant().choisirOffre(0);                                     // sans effet : la réponse est définitive
    composant().demanderConfirmation('REFUSER');
    expect(composant().offreSelectionnee).toBeNull();
    expect(un('.btn-accepter')).toBeNull();
    expect(un('.btn-refuser')).toBeNull();
  });

  it('formate les montants et pourcentages à la française', () => {
    demarrer();
    expect(composant().formatMontant(16300)).toMatch(/^16\s?300 DT$/);
    expect(composant().formatMontant(null)).toBe('—');
    expect(composant().formatPourcent(29.85)).toBe('29,85 %');
    expect(composant().formatPourcent(undefined)).toBe('—');
  });
});

describe('jwtInterceptor — routes « mes demandes »', () => {
  it('envoie le jeton du client (et non celui d\'un agent) sur les routes des propositions', async () => {
    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([jwtInterceptor])), provideHttpClientTesting(),
        { provide: AuthService, useValue: { getToken: () => 'jeton-agent' } },
        { provide: ClientAuthService, useValue: { getToken: () => 'jeton-client' } },
      ],
    }).compileComponents();
    const client = TestBed.inject(HttpClient);
    const http = TestBed.inject(HttpTestingController);

    client.get('/api/clients/mes-demandes/propositions').subscribe();
    expect(http.expectOne('/api/clients/mes-demandes/propositions').request.headers.get('Authorization')).toBe('Bearer jeton-client');

    client.get('/api/dossiers/1/resultat').subscribe();
    expect(http.expectOne('/api/dossiers/1/resultat').request.headers.get('Authorization')).toBe('Bearer jeton-agent');
  });
});
