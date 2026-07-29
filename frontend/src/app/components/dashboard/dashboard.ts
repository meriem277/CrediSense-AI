import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { CreditStateService } from '../../services/credit-state.service';
import { CommonModule } from '@angular/common';
import { UploadSection } from '../upload-section/upload-section';
import { HistoryList }   from '../Interne/history-list/history-list';
import { CreditResult }  from '../credit-result/credit-result';
import { ChatAssistant } from '../Interne/chat-assistant/chat-assistant';
import { ExportButton }  from '../Interne/export-button/export-button';
import { DashboardCard } from '../dashboard-card/dashboard-card';
import { ClientModal, ClientResponse } from '../client-modal/client-modal';
import { ClientList, Client }          from '../client-list/client-list';
import { Dossier as DossierComponent } from '../Interne/dossier/dossier';
import { Dossier as DossierModel }     from '../../models/dossier.model';
import { DossierService } from '../../services/Interne/dossier.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    UploadSection, HistoryList,
    CreditResult, ChatAssistant, ExportButton, DashboardCard,
    ClientModal, ClientList,
    DossierComponent,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {

  today = new Date();

  // ── Modals ──────────────────────────────────────────────
  showClientModal  = false;
  showDossierModal = false;

  // ── Niveau 1 : Client sélectionné ───────────────────────
  selectedClient: Client | null = null;

  // ── Niveau 2 : Dossiers du client ───────────────────────
  dossiers: DossierModel[] = [];
  loadingDossiers = false;

  // ── Niveau 3 : Dossier sélectionné ──────────────────────
  selectedDossier: DossierModel | null = null;
  currentCin = '';
  activeTab: 'documents' | 'score' | 'assistant' | 'historique' = 'documents';

  // ✅ Nouveaux — dossiers clients portail
  tousLesDossiers: any[] = [];
  loadingTousDossiers = false;
  filtreStatut = 'TOUS';

  private subs = new Subscription();

  constructor(
    private dossierService: DossierService,
    private creditState: CreditStateService,
      private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.subs.add(
      this.creditState.navigateToScore$.subscribe(trigger => {
        if (trigger && this.selectedDossier) {
          this.activeTab = 'score';
          this.creditState.resetNavigateToScore();
        }
      })
    );
    // ✅ Charge les dossiers portail client au démarrage
    this.loadTousDossiers();
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }

  // ── Sélection client ─────────────────────────────────────
  onClientSelected(client: Client): void {
    this.selectedClient  = client;
    this.currentCin      = client.cin;
    this.selectedDossier = null;
    this.dossiers        = [];
    this.loadDossiers(client.id);
  }

  // ── Chargement dossiers agent ────────────────────────────
  loadDossiers(clientId: string): void {
    this.loadingDossiers = true;
    this.dossierService.getByClientId(clientId).subscribe({
      next:  (data) => { this.dossiers = data; this.loadingDossiers = false; },
      error: ()     => { this.loadingDossiers = false; }
    });
  }

  // ✅ Chargement dossiers portail client (soumis par les clients)
  loadTousDossiers(): void {
    this.loadingTousDossiers = true;
    this.dossierService.getAllPortail().subscribe({
      next:  (data) => { this.tousLesDossiers = data; this.loadingTousDossiers = false; },
      error: ()     => { this.loadingTousDossiers = false; }
    });
  }

  // ✅ Dossiers filtrés par statut
  get dossiersPortailFiltres(): any[] {
    if (this.filtreStatut === 'TOUS') return this.tousLesDossiers;
    return this.tousLesDossiers.filter(d => d.statut === this.filtreStatut);
  }

  // ✅ Stats dossiers portail
  get totalPortail()    { return this.tousLesDossiers.length; }
  get enAttenteCount()  { return this.tousLesDossiers.filter(d => d.statut === 'EN_ATTENTE').length; }
  get enCoursCount()    { return this.tousLesDossiers.filter(d => d.statut === 'EN_COURS').length; }
  get approuvesCount()  { return this.tousLesDossiers.filter(d => d.statut === 'APPROUVE').length; }
  get refusesCount()    { return this.tousLesDossiers.filter(d => d.statut === 'REFUSE').length; }

  // ✅ Actions agent sur dossiers portail
 prendreEnCharge(dossierId: string): void {
  this.dossierService.updateStatut(dossierId, 'EN_COURS').subscribe({
    next: () => {
      this.loadTousDossiers();

      const dossierPortail = this.tousLesDossiers.find(d => d.dossierId === dossierId);
      if (!dossierPortail) return;

      // ✅ Sélectionne le client
      const client: Client = {
        id:        dossierPortail.clientId || '',
        cin:       dossierPortail.clientCin,
        nom:       dossierPortail.clientNom,
        prenom:    dossierPortail.clientPrenom,
        createdAt: ''
      };
      this.selectedClient = client;
      this.currentCin     = dossierPortail.clientCin;

      // ✅ Sélectionne directement le dossier
      const dossierModel: DossierModel = {
        id:         dossierId,
        typeCredit: dossierPortail.typeCredit,
        statut:     'EN_COURS',
       createdAt:  '',
        clientId:   dossierPortail.clientId || ''  // ✅ clientId au lieu de client
      };
      this.selectedDossier = dossierModel;
      this.activeTab       = 'documents';  // ✅ ouvre l'onglet documents
    }
  });
}

// ✅ Nouvelle méthode — charge les dossiers par email client
loadDossiersByEmail(email: string): void {
  this.loadingDossiers = true;
  this.http.get<any[]>(
    `${environment.apiUrl}/api/clients/historique?email=${email}`
  ).subscribe({
    next: (data) => {
      // ✅ Convertit les dossiers portail en DossierModel
      this.dossiers = data.map(d => ({
        id:         d.dossierId,
        clientId:   d.clientId ?? d.client_id ?? '',
        typeCredit: d.typeCredit,
        statut:     d.statut,
        createdAt:  d.createdAt ?? null,
        client:     null
      }));
      this.loadingDossiers = false;
    },
    error: () => { this.loadingDossiers = false; }
  });
}

  approuverDossier(id: string): void {
    this.dossierService.updateStatut(id, 'APPROUVE').subscribe({
      next: () => this.loadTousDossiers()
    });
  }

  refuserDossier(id: string): void {
    this.dossierService.updateStatut(id, 'REFUSE').subscribe({
      next: () => this.loadTousDossiers()
    });
  }

  getStatutPortailClass(statut: string): string {
    switch (statut) {
      case 'EN_ATTENTE': return 'statut-attente';
      case 'EN_COURS':   return 'statut-cours';
      case 'APPROUVE':   return 'statut-approuve';
      case 'REFUSE':     return 'statut-refuse';
      default:           return '';
    }
  }

  getStatutPortailLabel(statut: string): string {
    switch (statut) {
      case 'EN_ATTENTE': return 'En attente';
      case 'EN_COURS':   return 'En cours';
      case 'APPROUVE':   return 'Approuvé';
      case 'REFUSE':     return 'Refusé';
      default:           return statut;
    }
  }

  // ── Sélection dossier ─────────────────────────────────────
  selectDossier(dossier: DossierModel): void {
    this.selectedDossier = dossier;
    this.activeTab       = 'documents';
    console.log('Dossier sélectionné :', dossier.id);
  }

  // ── Retours navigation ────────────────────────────────────
  backToDossiers(): void { this.selectedDossier = null; }

  backToClients(): void {
    this.selectedClient  = null;
    this.selectedDossier = null;
    this.dossiers        = [];
  }

  // ── Modal client ──────────────────────────────────────────
  openClientModal():  void { this.showClientModal = true;  }
  closeClientModal(): void { this.showClientModal = false; }

  onClientCreated(client: ClientResponse): void {
    this.selectedClient = {
      id: client.id, cin: client.cin,
      nom: client.nom, prenom: client.prenom, createdAt: client.createdAt,
    };
    this.currentCin      = client.cin;
    this.selectedDossier = null;
    this.dossiers        = [];
    this.loadDossiers(client.id);
  }

  // ── Modal dossier ─────────────────────────────────────────
  openDossierModal():  void { this.showDossierModal = true;  }
  closeDossierModal(): void { this.showDossierModal = false; }

  onDossierCreated(dossier: DossierModel): void {
    this.dossiers.unshift(dossier);
    this.selectDossier(dossier);
  }

  // ── Helpers ──────────────────────────────────────────────
  getInitiales(client: Client): string {
    return (client.prenom?.[0] ?? '') + (client.nom?.[0] ?? '');
  }

  getTypeCreditLabel(type: string): string {
    const map: Record<string, string> = {
      IMMOBILIER:   '🏠 Immobilier',
      CONSOMMATION: '💳 Consommation',
    };
    return map[type] ?? type;
  }

  getStatutClass(statut: string): string {
    const map: Record<string, string> = {
      APPROUVE: 'approved',
      REFUSE:   'refused',
      EN_COURS: 'pending',
    };
    return map[statut] ?? 'pending';
  }

  get selectedDossierId(): string {
    const id = this.selectedDossier?.id ?? '';
    console.log('selectedDossierId getter:', id);
    return id;
  }
}
