import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../environments/environment';

export type VersionRapport = 'agent' | 'client';

/**
 * Télécharge le rapport PDF de la décision. Le PDF est généré par le serveur à partir du résultat
 * enregistré (rien n'est recalculé) : la version « agent » est complète, la version « client » est
 * celle qui part en pièce jointe de l'e-mail de réponse.
 */
@Component({
  selector: 'app-export-button',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './export-button.html',
  styleUrl: './export-button.scss',
})
export class ExportButton {
  @Input() dossierId: string | null = null;

  /** Version en cours de génération (un seul téléchargement à la fois). */
  enCours: VersionRapport | null = null;
  erreur = '';

  constructor(private http: HttpClient) {}

  telecharger(version: VersionRapport): void {
    if (!this.dossierId || this.enCours) return;

    this.enCours = version;
    this.erreur = '';

    this.http.get(
      `${environment.apiUrl}/api/dossiers/${this.dossierId}/rapport-pdf`,
      { params: { version }, responseType: 'blob' }
    ).subscribe({
      next: (pdf) => {
        this.enregistrer(pdf, this.nomFichier(version));
        this.enCours = null;
      },
      error: (err) => {
        this.enCours = null;
        this.erreur = err?.status === 404
          ? 'Aucun résultat enregistré pour ce dossier : lancez d\'abord l\'analyse.'
          : 'Le rapport PDF n\'a pas pu être généré. Veuillez réessayer.';
      }
    });
  }

  /** « Rapport-credit-1A2B3C4D.pdf » (agent) ou « Rapport-credit-1A2B3C4D-client.pdf ». */
  nomFichier(version: VersionRapport): string {
    const reference = (this.dossierId ?? 'dossier').substring(0, 8).toUpperCase();
    return `Rapport-credit-${reference}${version === 'client' ? '-client' : ''}.pdf`;
  }

  /** Déclenche l'enregistrement du fichier dans le navigateur. */
  protected enregistrer(pdf: Blob, nom: string): void {
    const adresse = URL.createObjectURL(pdf);
    const lien = document.createElement('a');
    lien.href = adresse;
    lien.download = nom;
    document.body.appendChild(lien);
    lien.click();
    lien.remove();
    URL.revokeObjectURL(adresse);
  }
}
