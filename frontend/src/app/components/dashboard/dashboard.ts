import { Component, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { CreditStateService } from '../../services/credit-state.service';
import { CommonModule } from '@angular/common';
import { UploadSection } from '../Interne/upload-section/upload-section';
import { CreditResult }  from '../Interne/credit-result/credit-result';
import { ChatAssistant } from '../Interne/chat-assistant/chat-assistant';
import { ExportButton }  from '../Interne/export-button/export-button';
import { DashboardCard } from '../dashboard-card/dashboard-card';
import { Dossier as DossierModel }     from '../../models/dossier.model';
import { DossierService } from '../../services/Interne/dossier.service';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/Interne/auth.service';
import { jwtDecode } from 'jwt-decode';
import { DecodedToken } from '../../models/auth.model';

// ✅ Client n'est plus fourni par ClientList (supprimé) — on le redéfinit ici.
// Si tu as un fichier dédié (ex. models/client.model.ts), remplace cette
// interface par : import { Client } from '../../models/client.model';
interface Client {
  id: string;
  cin: string;
  nom: string;
  prenom: string;
  createdAt: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    UploadSection,
    CreditResult, ChatAssistant, DashboardCard,

    FormsModule
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
  // ✅ Restaurée — utilisée dans prendreEnCharge, backToClients, onClientCreated, getInitiales
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
    private http: HttpClient,
    private authService: AuthService

  ) {}

  ngOnInit(): void {
    this.loadAgentInfo();

    this.subs.add(
      this.creditState.navigateToScore$.subscribe(trigger => {
        if (trigger && this.selectedDossier) {
          this.activeTab = 'score';
          this.creditState.resetNavigateToScore();
        }
      })
    );

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

        const client: Client = {
          id:        dossierPortail.clientId || '',
          cin:       dossierPortail.clientCin,
          nom:       dossierPortail.clientNom,
          prenom:    dossierPortail.clientPrenom,
          createdAt: ''
        };
        this.selectedClient = client;
        this.currentCin     = dossierPortail.clientCin;

        const dossierModel: DossierModel = {
          id:         dossierId,
          typeCredit: dossierPortail.typeCredit,
          statut:     'EN_COURS',
          createdAt:  '',
          clientId:   dossierPortail.clientId || ''
        };
        this.selectedDossier = dossierModel;
        this.activeTab       = 'documents';
      }
    });
  }

  // ✅ Chargement des dossiers par email client
  loadDossiersByEmail(email: string): void {
    this.loadingDossiers = true;
    this.http.get<any[]>(
      `${environment.apiUrl}/api/clients/historique?email=${email}`
    ).subscribe({
      next: (data) => {
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

  onAnalysisComplete(): void {
    this.loadingTousDossiers = true;
    this.dossierService.getAllPortail().subscribe({
      next: (data) => {
        this.tousLesDossiers = data;
        this.loadingTousDossiers = false;
      },
      error: () => { this.loadingTousDossiers = false; }
    });
  }

  // ── Pagination portail ─────────────────────────────────────
  pageSize    = 5;
  currentPage = 1;

  get dossiersPortailFiltres(): any[] {
    let list = this.filtreStatut === 'TOUS'
      ? this.tousLesDossiers
      : this.tousLesDossiers.filter(d => d.statut === this.filtreStatut);

    if (this.searchPortail) {
      const q = this.searchPortail.toLowerCase();
      list = list.filter(d =>
        d.clientNom?.toLowerCase().includes(q)    ||
        d.clientPrenom?.toLowerCase().includes(q) ||
        d.clientCin?.toLowerCase().includes(q)    ||
        d.clientEmail?.toLowerCase().includes(q)
      );
    }

    return [...list].sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  get dossiersPage(): any[] {
    const start = (this.currentPage - 1) * this.pageSize;
    return this.dossiersPortailFiltres.slice(start, start + this.pageSize);
  }

  get totalPages(): number {
    return Math.ceil(this.dossiersPortailFiltres.length / this.pageSize);
  }

  get pages(): number[] {
    return Array.from({ length: this.totalPages }, (_, i) => i + 1);
  }

  goToPage(p: number): void {
    if (p < 1 || p > this.totalPages) return;
    this.currentPage = p;
  }

  searchPortail = '';

  onSearchPortail(): void {
    this.currentPage = 1;
  }

  setFiltreStatut(statut: string): void {
    this.filtreStatut  = statut;
    this.currentPage   = 1;
  }

  agentNom: string = '';
  agentPrenom: string = '';
private loadAgentInfo(): void {
  const token = this.authService.getToken();
  if (!token) {
    this.agentNom = 'Agent';
    this.agentPrenom = '';
    return;
  }

  try {
    const decoded = jwtDecode<DecodedToken>(token);
    const parts = (decoded.nom || '').trim().split(' ');
    this.agentPrenom = this.capitalize(parts[0] ?? '');
    this.agentNom = this.capitalize(parts.slice(1).join(' ') || parts[0] || 'Agent');
  } catch (e) {
    console.error('Erreur décodage token:', e);
    this.agentNom = 'Agent';
    this.agentPrenom = '';
  }
}

private capitalize(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}
}
