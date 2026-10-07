import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ChatAssistant, HISTORIQUE_MAX_MESSAGES } from './chat-assistant';
import { environment } from '../../../../environments/environment';

describe('ChatAssistant — mémoire de conversation', () => {
  let component: ChatAssistant;
  let fixture: ComponentFixture<ChatAssistant>;
  let http: HttpTestingController;

  const url = `${environment.apiUrl}/api/chatbot/question`;

  const salutation = () => ({
    role: 'assistant' as const,
    content: 'Bonjour ! Je suis CrediSense.',
    timestamp: new Date(),
    technique: true,
  });

  /** Pose une question et renvoie le corps envoyé au serveur ; le serveur répond ensuite. */
  function poser(question: string, reponse: string): any {
    component.question = question;
    component.envoyer();
    const requete = http.expectOne(url);
    const corps = requete.request.body;
    requete.flush({ reponse });
    return corps;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatAssistant],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatAssistant);
    component = fixture.componentInstance;
    component.cin = '12015060';
    component.dossierId = 'dossier-1';
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    component.messages = [salutation()];
  });

  afterEach(() => http.verify());

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('première question : historique vide (la salutation n\'est pas un échange)', () => {
    const corps = poser('Quel est le net à payer ?', '2 100,000 DT.');

    expect(corps.question).toBe('Quel est le net à payer ?');
    expect(corps.historique).toEqual([]);
  });

  it('deuxième question : la première question et sa réponse sont envoyées, dans l\'ordre', () => {
    poser('Quel est le net à payer ?', '2 100,000 DT.');
    const corps = poser('Et le prêt ?', '250,000 DT par mois.');

    expect(corps.question).toBe('Et le prêt ?');
    expect(corps.historique).toEqual([
      { role: 'user',      content: 'Quel est le net à payer ?' },
      { role: 'assistant', content: '2 100,000 DT.' },
    ]);
  });

  it('la question en cours n\'est jamais dans l\'historique', () => {
    const corps = poser('Question unique', 'Réponse.');

    expect(JSON.stringify(corps.historique)).not.toContain('Question unique');
  });

  it('seuls les derniers messages sont envoyés', () => {
    for (let i = 0; i < 5; i++) poser(`question ${i}`, `réponse ${i}`);
    const corps = poser('question 5', 'réponse 5');

    expect(corps.historique.length).toBe(HISTORIQUE_MAX_MESSAGES);
    // 5 échanges = 10 messages ; les 6 derniers vont de « question 2 » à « réponse 4 »
    expect(corps.historique[0]).toEqual({ role: 'user', content: 'question 2' });
    expect(corps.historique[5]).toEqual({ role: 'assistant', content: 'réponse 4' });
  });

  it('un message d\'erreur de l\'interface n\'entre pas dans l\'historique', () => {
    component.question = 'Première question';
    component.envoyer();
    http.expectOne(url).flush('panne', { status: 500, statusText: 'Erreur serveur' });   // « Désolé, une erreur… »

    const corps = poser('Deuxième question', 'Réponse.');

    expect(corps.historique).toEqual([{ role: 'user', content: 'Première question' }]);
  });

  it('historiquePourEnvoi ignore les messages vides et ne modifie pas l\'affichage', () => {
    component.messages = [
      salutation(),
      { role: 'user', content: '  ', timestamp: new Date() },
      { role: 'user', content: 'Vraie question', timestamp: new Date() },
    ];

    expect(component.historiquePourEnvoi()).toEqual([{ role: 'user', content: 'Vraie question' }]);
    expect(component.messages.length).toBe(3);
  });
});
