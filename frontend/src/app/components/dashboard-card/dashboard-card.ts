import { DatePipe } from '@angular/common';
import { AfterViewInit, Component, ElementRef, EventEmitter, Output, ViewChild } from '@angular/core';

@Component({
  selector: 'app-dashboard-card',
  imports: [DatePipe],
  templateUrl: './dashboard-card.html',
  styleUrl: './dashboard-card.scss',
})
export class DashboardCard implements AfterViewInit {

 @ViewChild('donutCircle') donutCircle!: ElementRef<SVGCircleElement>;
  @ViewChild('barFill') barFill!: ElementRef<HTMLDivElement>;
@Output() onNouveauClient = new EventEmitter<void>();

  today = new Date();

  dossiersTraites = 0;
  tauxApprobation = 0;
  dossiersRefuses = 0;

  private readonly TARGET_TRAITES = 128;
  private readonly TARGET_TAUX = 74;
  private readonly TARGET_REFUSES = 33;
  private readonly DONUT_CIRCUMFERENCE = 201; // 2 * PI * r(32)
  private readonly REFUS_PERCENT = 26;

  ngAfterViewInit() {
    setTimeout(() => {
      this.animateCount('dossiersTraites', this.TARGET_TRAITES, 1400);
      this.animateCount('tauxApprobation', this.TARGET_TAUX, 1400);
      this.animateCount('dossiersRefuses', this.TARGET_REFUSES, 1400);
      this.animateDonut();
      this.animateBar();
    }, 100);
  }

  private animateCount(prop: 'dossiersTraites' | 'tauxApprobation' | 'dossiersRefuses', target: number, duration: number) {
    const startTime = performance.now();

    const step = (now: number) => {
      const progress = Math.min((now - startTime) / duration, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      this[prop] = Math.floor(eased * target);

      if (progress < 1) {
        requestAnimationFrame(step);
      } else {
        this[prop] = target;
      }
    };

    requestAnimationFrame(step);
  }

  private animateDonut() {
    if (!this.donutCircle) return;
    const offset = this.DONUT_CIRCUMFERENCE - (this.TARGET_TAUX / 100) * this.DONUT_CIRCUMFERENCE;
    requestAnimationFrame(() => {
      this.donutCircle.nativeElement.style.strokeDashoffset = `${offset}`;
    });
  }

  private animateBar() {
    if (!this.barFill) return;
    requestAnimationFrame(() => {
      this.barFill.nativeElement.style.width = `${this.REFUS_PERCENT}%`;
    });
  }

  openClientModal() {
    // sera relié par un @Output() — voir note plus bas
  }
}
