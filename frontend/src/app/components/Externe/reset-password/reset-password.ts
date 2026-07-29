import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ClientAuthService } from '../../../services/Externe/Client-auth.service';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-reset-password',
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './reset-password.html',
  styleUrl: './reset-password.scss',
})
export class ResetPassword  implements OnInit  {
   token = '';
  password = '';
  confirmPassword = '';
  loading = false;
  errorMsg = '';
  successMsg = '';
  tokenInvalid = false;

  passwordStrength = { percent: 0, color: '#e2e8f0', label: '' };
  passwordErrors: string[] = [];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private clientAuth: ClientAuthService
  ) {}

  ngOnInit() {
    // ✅ Récupère le token depuis l'URL
    this.token = this.route.snapshot.queryParamMap.get('token') || '';
    if (!this.token) {
      this.tokenInvalid = true;
    }
  }

  // ── Validation mot de passe ───────────────────────────────────────
  checkPassword(value: string) {
    this.passwordErrors = [];
    let score = 0;

    if (value.length >= 8)     score++;
    if (/[A-Z]/.test(value))   score++;
    if (/[0-9]/.test(value))   score++;
    if (/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value)) score++;
    if (value.length >= 12)    score++;

    switch (score) {
      case 0:
      case 1: this.passwordStrength = { percent: 20, color: '#dc2626', label: 'Très faible' }; break;
      case 2: this.passwordStrength = { percent: 40, color: '#f97316', label: 'Faible' }; break;
      case 3: this.passwordStrength = { percent: 60, color: '#eab308', label: 'Moyen' }; break;
      case 4: this.passwordStrength = { percent: 80, color: '#22c55e', label: 'Fort' }; break;
      case 5: this.passwordStrength = { percent: 100, color: '#16a34a', label: 'Très fort' }; break;
    }

    if (value.length < 8)
      this.passwordErrors.push('Au moins 8 caractères');
    if (!/[A-Z]/.test(value))
      this.passwordErrors.push('Au moins une majuscule');
    if (!/[0-9]/.test(value))
      this.passwordErrors.push('Au moins un chiffre');
    if (!/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value))
      this.passwordErrors.push('Au moins un caractère spécial');
  }

  // ── Soumission ────────────────────────────────────────────────────
  onSubmit() {
    this.errorMsg = '';
    this.successMsg = '';

    if (!this.password || !this.confirmPassword) {
      this.errorMsg = 'Veuillez remplir tous les champs.';
      return;
    }

    if (this.passwordErrors.length > 0) {
      this.errorMsg = this.passwordErrors[0];
      return;
    }

    if (this.password !== this.confirmPassword) {
      this.errorMsg = 'Les mots de passe ne correspondent pas.';
      return;
    }

    this.loading = true;
    this.clientAuth.resetPassword(this.token, this.password).subscribe({
      next: () => {
        this.loading = false;
        this.successMsg = '✅ Mot de passe réinitialisé avec succès !';
        // ✅ Redirige vers login après 2 secondes
        setTimeout(() => this.router.navigate(['/client/login']), 2000);
      },
      error: (err: any) => {
        const msg = err.error?.message || '';
        if (msg.includes('expiré') || msg.includes('invalide')) {
          this.errorMsg = '⏱ Ce lien a expiré ou est invalide. Faites une nouvelle demande.';
        } else {
          this.errorMsg = msg || 'Erreur lors de la réinitialisation.';
        }
        this.loading = false;
      }
    });
  }
}
