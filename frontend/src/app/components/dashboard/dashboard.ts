import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { CreditStateService } from '../../services/credit-state.service';
import { CommonModule } from '@angular/common';
import { Sidebar }       from '../sidebar/sidebar';
import { Header }        from '../header/header';
import { UploadSection } from '../upload-section/upload-section';
import { HistoryList }   from '../history-list/history-list';
import { CreditResult }  from '../credit-result/credit-result';
import { ChatAssistant } from '../chat-assistant/chat-assistant';
import { ExportButton }  from '../export-button/export-button';
import { DashboardCard } from '../dashboard-card/dashboard-card';
import { ClientModal, ClientResponse } from '../client-modal/client-modal';
import { ClientList, Client }          from '../client-list/client-list';

// ✅ Import du composant avec alias pour éviter le conflit de nom
import { Dossier as DossierComponent } from '../dossier/dossier';

// ✅ Import du type depuis le model
import { Dossier as DossierModel }     from '../../models/dossier.model';

import { DossierService } from '../../services/dossier.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    Sidebar, Header, UploadSection, HistoryList,
    CreditResult, ChatAssistant, ExportButton, DashboardCard,
    ClientModal, ClientList,
    DossierComponent,   // ✅ le composant avec son alias
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {

  // ── Modals ─────────────────────────────────────────────
  showClientModal  = false;
  showDossierModal = false;

  // ── Niveau 1 : Client sélectionné ──────────────────────
  selectedClient: Client | null = null;

  // ── Niveau 2 : Dossiers du client ──────────────────────
  dossiers: DossierModel[] = [];           // ✅ type DossierModel
  loadingDossiers = false;

  // ── Niveau 3 : Dossier sélectionné ─────────────────────
  selectedDossier: DossierModel | null = null;  // ✅ type DossierModel
  currentCin = '';
  activeTab: 'documents' | 'score' | 'assistant' | 'historique' = 'documents';

  private subs = new Subscription();

  constructor(private dossierService: DossierService, private creditState: CreditStateService) {}

  ngOnInit(): void {
    this.subs.add(
      this.creditState.navigateToScore$.subscribe(trigger => {
        if (trigger && this.selectedDossier) {
          this.activeTab = 'score';
          this.creditState.resetNavigateToScore();
        }
      })
    );
  }

  ngOnDestroy(): void { this.subs.unsubscribe(); }

  // ── Sélection client ────────────────────────────────────
  onClientSelected(client: Client): void {
    this.selectedClient  = client;
    this.currentCin      = client.cin;
    this.selectedDossier = null;
    this.dossiers        = [];
    this.loadDossiers(client.id);
  }

  // ── Chargement dossiers ─────────────────────────────────
  loadDossiers(clientId: string): void {
    this.loadingDossiers = true;
    this.dossierService.getByClientId(clientId).subscribe({
      next:  (data) => { this.dossiers = data; this.loadingDossiers = false; },
      error: ()     => { this.loadingDossiers = false; }
    });
  }

  // ── Sélection dossier ───────────────────────────────────
 selectDossier(dossier: DossierModel): void {
  this.selectedDossier = dossier;
  this.activeTab       = 'documents';
  console.log('Dossier sélectionné :', dossier.id);
}
  // ── Retours navigation ──────────────────────────────────
  backToDossiers(): void { this.selectedDossier = null; }

  backToClients(): void {
    this.selectedClient  = null;
    this.selectedDossier = null;
    this.dossiers        = [];
  }

  // ── Modal client ────────────────────────────────────────
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

  // ── Modal dossier ───────────────────────────────────────
  openDossierModal():  void { this.showDossierModal = true;  }
  closeDossierModal(): void { this.showDossierModal = false; }

  onDossierCreated(dossier: DossierModel): void {  // ✅ type DossierModel
    this.dossiers.unshift(dossier);
    this.selectDossier(dossier);
  }

  // ── Helpers ─────────────────────────────────────────────
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
  return id;}


}
