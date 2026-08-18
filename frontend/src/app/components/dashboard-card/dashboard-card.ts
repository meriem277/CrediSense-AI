import { DatePipe, CommonModule } from '@angular/common';
import { AfterViewInit, Component, ElementRef, EventEmitter, Input, OnChanges, Output, SimpleChanges, ViewChild } from '@angular/core';

interface DossierLite {
  id?: string;
  statut?: string;
}

@Component({
  selector:    'app-dashboard-card',
  standalone:  true,
  imports:     [DatePipe, CommonModule],
  templateUrl: './dashboard-card.html',
  styleUrl:    './dashboard-card.scss',
})
export class DashboardCard implements AfterViewInit, OnChanges {

  @ViewChild('donutCircle') donutCircle!: ElementRef<SVGCircleElement>;
  @ViewChild('barFill')     barFill!:     ElementRef<HTMLDivElement>;

  @Output() onNouveauClient = new EventEmitter<void>();

  @Input() agentNom:    string = 'Agent';
  @Input() agentPrenom: string = '';

  // ✅ Liste réelle des dossiers, fournie par le parent (déjà chargée via getAllPortail())
  @Input() dossiers: DossierLite[] = [];

  today = new Date();

  dossiersTraites = 0;
  dossiersRefuses = 0;
  dossiersApprouves = 0;
  tauxApprobation = 0;

  private readonly DONUT_CIRCUMFERENCE = 201;
  private viewReady = false;

  get greeting(): string {
    const h = new Date().getHours();
    if (h < 12) return 'Bonjour';
    if (h < 18) return 'Bon après-midi';
    return 'Bonsoir';
  }

  get pourcentageRefuses(): number {
    return this.dossiersTraites > 0
      ? Math.round((this.dossiersRefuses / this.dossiersTraites) * 100)
      : 0;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['dossiers']) {
      this.computeStats();
      if (this.viewReady) {
        this.animateDonut();
        this.animateBar();
      }
    }
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.computeStats();
    setTimeout(() => {
      this.animateDonut();
      this.animateBar();
    }, 100);
  }

  private computeStats(): void {
    const list = this.dossiers ?? [];
    this.dossiersTraites   = list.length;
    this.dossiersRefuses   = list.filter(d => d.statut === 'REFUSE').length;
    this.dossiersApprouves = list.filter(d => d.statut === 'APPROUVE').length;
    this.tauxApprobation   = this.dossiersTraites > 0
      ? Math.round((this.dossiersApprouves / this.dossiersTraites) * 100)
      : 0;
  }

  private animateDonut(): void {
    if (!this.donutCircle) return;
    const offset = this.DONUT_CIRCUMFERENCE - (this.tauxApprobation / 100) * this.DONUT_CIRCUMFERENCE;
    requestAnimationFrame(() => {
      this.donutCircle.nativeElement.style.strokeDashoffset = `${offset}`;
    });
  }

  private animateBar(): void {
    if (!this.barFill) return;
    requestAnimationFrame(() => {
      this.barFill.nativeElement.style.width = `${this.pourcentageRefuses}%`;
    });
  }
}
