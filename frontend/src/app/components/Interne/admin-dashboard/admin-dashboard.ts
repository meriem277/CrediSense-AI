import { environment } from '../../../../environments/environment';
import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../../services/Interne/auth.service';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Register } from '../register/register';

@Component({
  selector: 'app-admin-dashboard',
  imports: [CommonModule, ReactiveFormsModule, Register],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.scss',
})
export class AdminDashboard implements OnInit {
  // Données admin
  nom = '';
  activeTab = 'overview';  // 'overview' | 'agents' | 'dossiers' | 'register'
  today = new Date();

  // Formulaire register
  registerForm: FormGroup;
  registerLoading = false;
  registerError = '';
  registerSuccess = '';

  // Liste des agents
  agents: any[] = [];

  // Tous les dossiers (partagé entre heatmap, onglet Dossiers et stats bancaires)
  tousLesDossiers: any[] = [];
  filtreDossierStatut = 'TOUS';

  // Heatmap activité des dossiers
  jours = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];
  creneaux = ['00-04h', '04-08h', '08-12h', '12-16h', '16-20h', '20-24h'];
  heatmapData: number[][] = [];
  periodeHeatmap = '';
  weekOffset = 0; // 0 = semaine actuelle, -1 = semaine précédente, etc.

  constructor(
    private auth: AuthService,
    private fb: FormBuilder,
    private http: HttpClient,
    private router: Router
  ) {
    this.registerForm = this.fb.group({
      nom:      ['', Validators.required],
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(8)]],
      role:     ['AGENT', Validators.required]
    });
  }

  ngOnInit() {
    const user = this.auth.getUser();
    this.nom = user?.nom || 'Admin';
    this.loadAgents();
    this.loadDossiersHeatmap();
  }

  loadAgents() {
    this.http.get<any[]>(environment.apiUrl + '/api/agents')
      .subscribe({ next: (data) => this.agents = data, error: () => {} });
  }

  loadDossiersHeatmap() {
    this.http.get<any[]>(environment.apiUrl + '/api/dossiers')
      .subscribe({
        next: (dossiers) => {
          this.tousLesDossiers = dossiers;
          this.buildHeatmap();
        },
        error: () => {
          this.tousLesDossiers = [];
          this.buildHeatmap();
        }
      });
  }

  // ── Statistiques bancaires ──────────────────────────────────────────────

  get enAttenteCount(): number {
    return this.tousLesDossiers.filter(d => d.statut === 'EN_ATTENTE').length;
  }

  get pourcentageEnAttente(): number {
    if (this.tousLesDossiers.length === 0) return 0;
    return Math.round((this.enAttenteCount / this.tousLesDossiers.length) * 100);
  }

  /**
   * Taux d'approbation global : proportion de dossiers APPROUVE parmi les
   * dossiers déjà tranchés (APPROUVE + REFUSE) — les dossiers EN_ATTENTE
   * ou EN_COURS ne sont pas comptés car pas encore de décision.
   */
  get tauxApprobation(): number {
    const traites = this.tousLesDossiers.filter(d => d.statut === 'APPROUVE' || d.statut === 'REFUSE');
    if (traites.length === 0) return 0;
    const approuves = traites.filter(d => d.statut === 'APPROUVE').length;
    return Math.round((approuves / traites.length) * 100);
  }

  get montantMoyenDemande(): number {
    const montants = this.tousLesDossiers
      .map(d => d.montantCredit)
      .filter(m => m && m > 0);
    if (montants.length === 0) return 0;
    return Math.round(montants.reduce((a, b) => a + b, 0) / montants.length);
  }

  get classementAgents(): { nom: string; total: number; approuves: number; taux: number }[] {
    const groupes = new Map<string, { total: number; approuves: number }>();

    this.tousLesDossiers
      .filter(d => d.agentNom && d.agentNom.trim() !== '')
      .forEach(d => {
        const entry = groupes.get(d.agentNom) || { total: 0, approuves: 0 };
        entry.total++;
        if (d.statut === 'APPROUVE') entry.approuves++;
        groupes.set(d.agentNom, entry);
      });

    return Array.from(groupes.entries())
      .map(([nom, v]) => ({
        nom,
        total: v.total,
        approuves: v.approuves,
        taux: v.total > 0 ? Math.round((v.approuves / v.total) * 100) : 0
      }))
      .sort((a, b) => b.total - a.total)
      .slice(0, 5);
  }

  // ── Onglet Dossiers ───────────────────────────────────────────────────
  get dossiersFiltres(): any[] {
    return this.filtreDossierStatut === 'TOUS'
      ? this.tousLesDossiers
      : this.tousLesDossiers.filter(d => d.statut === this.filtreDossierStatut);
  }

  setFiltreDossierStatut(statut: string): void {
    this.filtreDossierStatut = statut;
  }

  getDossierStatutClass(statut: string): string {
    const map: Record<string, string> = {
      EN_ATTENTE: 'statut-attente',
      EN_COURS:   'statut-cours',
      APPROUVE:   'statut-approuve',
      REFUSE:     'statut-refuse',
    };
    return map[statut] || '';
  }

  getDossierStatutLabel(statut: string): string {
    const map: Record<string, string> = {
      EN_ATTENTE: 'En attente',
      EN_COURS:   'En cours',
      APPROUVE:   'Approuvé',
      REFUSE:     'Refusé',
    };
    return map[statut] || statut;
  }

  // ── Heatmap ──────────────────────────────────────────────────────────
  private getWeekRange(offset: number): { debut: Date; fin: Date } {
    const now = new Date();
    const jourActuel = (now.getDay() + 6) % 7; // Lun=0..Dim=6
    const lundi = new Date(now);
    lundi.setHours(0, 0, 0, 0);
    lundi.setDate(now.getDate() - jourActuel + offset * 7);

    const dimanche = new Date(lundi);
    dimanche.setDate(lundi.getDate() + 6);
    dimanche.setHours(23, 59, 59, 999);

    return { debut: lundi, fin: dimanche };
  }

  private buildHeatmap(): void {
    const matrix: number[][] = Array.from({ length: 7 }, () => Array(6).fill(0));
    const { debut, fin } = this.getWeekRange(this.weekOffset);

    this.tousLesDossiers.forEach(d => {
      if (!d.createdAt) return;
      const date = new Date(d.createdAt);
      if (date < debut || date > fin) return;

      // getDay() : 0=Dimanche..6=Samedi → on remappe pour Lun=0..Dim=6
      const jourIndex = (date.getDay() + 6) % 7;
      const creneauIndex = Math.floor(date.getHours() / 4);

      matrix[jourIndex][creneauIndex]++;
    });

    this.heatmapData = matrix;

    const fmt = (dt: Date) => dt.toLocaleDateString('fr-FR', { day: '2-digit', month: 'short' });
    this.periodeHeatmap = `${fmt(debut)} — ${fmt(fin)}`;
  }

  semainePrecedente(): void {
    this.weekOffset--;
    this.buildHeatmap();
  }

  semaineSuivante(): void {
    if (this.weekOffset === 0) return; // pas de semaines futures
    this.weekOffset++;
    this.buildHeatmap();
  }

  allerSemaineActuelle(): void {
    this.weekOffset = 0;
    this.buildHeatmap();
  }

  getCellColor(count: number): string {
    if (count === 0) return '#f3f4f6';
    const max = Math.max(1, ...this.heatmapData.flat());
    const intensity = count / max;
    if (intensity > 0.75) return '#e8302a';
    if (intensity > 0.5)  return '#f47920';
    if (intensity > 0.25) return '#f9a862';
    return '#fcd9b8';
  }

  // ── Register ─────────────────────────────────────────────────────────
  submitRegister() {
    if (this.registerForm.invalid) return;
    this.registerLoading = true;
    this.registerError = '';
    this.registerSuccess = '';

    this.http.post<any>(
      environment.apiUrl + '/api/auth/register',
      this.registerForm.value
    ).subscribe({
      next: () => {
        this.registerSuccess = 'Compte créé avec succès.';
        this.registerForm.reset({ role: 'AGENT' });
        this.registerLoading = false;
        this.loadAgents();
      },
      error: (err) => {
        this.registerError = err.error?.message || 'Erreur lors de la création.';
        this.registerLoading = false;
      }
    });
  }
  get dossiersTranchesCount(): number {
  return this.tousLesDossiers.filter(d => d.statut === 'APPROUVE' || d.statut === 'REFUSE').length;
}

  logout() {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
