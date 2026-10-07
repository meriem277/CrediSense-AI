import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { vi } from 'vitest';

import { ExportButton } from './export-button';
import { environment } from '../../../../environments/environment';

describe('ExportButton — rapport PDF', () => {
  let fixture: ComponentFixture<ExportButton>;
  let composant: ExportButton;
  let http: HttpTestingController;
  let enregistrer: ReturnType<typeof vi.fn>;

  const DOSSIER = '1a2b3c4d-0000-0000-0000-000000000000';
  const url = `${environment.apiUrl}/api/dossiers/${DOSSIER}/rapport-pdf`;
  const pdf = () => new Blob(['%PDF-test'], { type: 'application/pdf' });

  const bouton = (classe: string) =>
    fixture.nativeElement.querySelector(`button.${classe}`) as HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ExportButton],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(ExportButton);
    composant = fixture.componentInstance;
    composant.dossierId = DOSSIER;
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);

    // pas de vrai téléchargement dans le test
    enregistrer = vi.fn();
    (composant as any).enregistrer = enregistrer;
  });

  afterEach(() => http.verify());

  it('should create', () => {
    expect(composant).toBeTruthy();
  });

  it('le rapport complet est demandé au serveur en PDF et enregistré sous un nom lisible', () => {
    composant.telecharger('agent');

    const requete = http.expectOne(r => r.url === url);
    expect(requete.request.method).toBe('GET');
    expect(requete.request.params.get('version')).toBe('agent');
    expect(requete.request.responseType).toBe('blob');
    requete.flush(pdf());

    expect(enregistrer).toHaveBeenCalledTimes(1);
    expect(enregistrer.mock.calls[0][1]).toBe('Rapport-credit-1A2B3C4D.pdf');
    expect(composant.enCours).toBeNull();
  });

  it('la version client a son propre nom de fichier', () => {
    composant.telecharger('client');

    const requete = http.expectOne(r => r.url === url);
    expect(requete.request.params.get('version')).toBe('client');
    requete.flush(pdf());

    expect(enregistrer.mock.calls[0][1]).toBe('Rapport-credit-1A2B3C4D-client.pdf');
  });

  it('un seul téléchargement à la fois : les boutons sont bloqués pendant la génération', () => {
    composant.telecharger('agent');
    fixture.detectChanges();

    expect(bouton('export-agent').disabled).toBe(true);
    expect(bouton('export-client').disabled).toBe(true);
    expect(bouton('export-agent').textContent).toContain('Génération');

    composant.telecharger('client');            // ignoré : pas de seconde requête
    http.expectOne(r => r.url === url).flush(pdf());
    fixture.detectChanges();

    expect(bouton('export-agent').disabled).toBe(false);
    expect(enregistrer).toHaveBeenCalledTimes(1);
  });

  it('sans dossier, rien n\'est demandé et les boutons sont inactifs', () => {
    composant.dossierId = null;
    fixture.detectChanges();

    composant.telecharger('agent');

    http.expectNone(r => r.url.includes('rapport-pdf'));
    expect(bouton('export-agent').disabled).toBe(true);
  });

  it('aucun résultat enregistré (404) : message clair', () => {
    composant.telecharger('agent');
    http.expectOne(r => r.url === url).error(new ProgressEvent('error'), { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(composant.erreur).toContain('lancez d\'abord l\'analyse');
    expect(fixture.nativeElement.querySelector('.export-erreur')?.textContent).toContain('lancez d\'abord l\'analyse');
    expect(enregistrer).not.toHaveBeenCalled();
  });

  it('erreur du serveur : message générique, et on peut réessayer', () => {
    composant.telecharger('agent');
    http.expectOne(r => r.url === url).error(new ProgressEvent('error'), { status: 500, statusText: 'Erreur serveur' });

    expect(composant.erreur).toContain('n\'a pas pu être généré');
    expect(composant.enCours).toBeNull();

    composant.telecharger('agent');             // nouvel essai : l'erreur précédente disparaît
    expect(composant.erreur).toBe('');
    http.expectOne(r => r.url === url).flush(pdf());
    expect(enregistrer).toHaveBeenCalledTimes(1);
  });

  it('nom de fichier sans identifiant de dossier', () => {
    composant.dossierId = null;

    expect(composant.nomFichier('agent')).toBe('Rapport-credit-DOSSIER.pdf');
  });
});
