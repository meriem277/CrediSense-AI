import { AfterViewInit, Component, OnInit } from '@angular/core';
import { ClientAuthService } from '../../../services/Externe/Client-auth.service';
import { Router, RouterModule } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

declare var google: any;
function validatePassword(password: string): string | null {
  if (password.length < 8)
    return 'Au moins 8 caractères';
  if (!/[A-Z]/.test(password))
    return 'Au moins une lettre majuscule';
  if (!/[0-9]/.test(password))
    return 'Au moins un chiffre';
  if (!/[!@#$%^&*()_+\-=\[\]{};\':"\\|,.<>\/?]/.test(password))
    return 'Au moins un caractère spécial (!@#$...)';
  return null;
}

@Component({
  selector: 'app-clientloginetregister',
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './clientloginetregister.html',
  styleUrl: './clientloginetregister.scss',
})
export class Clientloginetregister implements AfterViewInit {

mode: 'login' | 'register' | 'forgot' = 'login';
successMsg = ''
  loading = false;
  errorMsg = '';

  form = {
    email: '',
    password: '',
    confirmPassword: '',
    nom: '',
    prenom: ''
  };
passwordErrors: string[] = [];
passwordStrength = { percent: 0, color: '#e2e8f0', label: '' };

  constructor(
    private clientAuth: ClientAuthService,
    private router: Router
  ) {}
onForgotPassword() {
  this.errorMsg = '';
  this.successMsg = '';

  if (!this.form.email) {
    this.errorMsg = 'Veuillez entrer votre email.';
    return;
  }

  this.loading = true;
  this.clientAuth.forgotPassword(this.form.email).subscribe({
    next: () => {
      this.loading = false;
      this.successMsg = '📧 Email envoyé ! Vérifiez votre boîte mail.';
    },
    error: (err: any) => {
      this.errorMsg = err.error?.message || 'Erreur lors de l\'envoi.';
      this.loading = false;
    }
  });
}

checkPassword(value: string) {
  this.passwordErrors = [];
  let score = 0;

  if (value.length >= 8)      score++;
  if (/[A-Z]/.test(value))    score++;
  if (/[0-9]/.test(value))    score++;
  if (/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value)) score++;
  if (value.length >= 12)     score++;  // bonus longueur

  // ✅ Mise à jour de la barre
  switch (score) {
    case 0:
    case 1:
      this.passwordStrength = { percent: 20, color: '#dc2626', label: 'Très faible' };
      break;
    case 2:
      this.passwordStrength = { percent: 40, color: '#f97316', label: 'Faible' };
      break;
    case 3:
      this.passwordStrength = { percent: 60, color: '#eab308', label: 'Moyen' };
      break;
    case 4:
      this.passwordStrength = { percent: 80, color: '#22c55e', label: 'Fort' };
      break;
    case 5:
      this.passwordStrength = { percent: 100, color: '#16a34a', label: 'Très fort' };
      break;
  }

  // Règles
  if (value.length < 8)
    this.passwordErrors.push('Au moins 8 caractères');
  if (!/[A-Z]/.test(value))
    this.passwordErrors.push('Au moins une majuscule');
  if (!/[0-9]/.test(value))
    this.passwordErrors.push('Au moins un chiffre');
  if (!/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value))
    this.passwordErrors.push('Au moins un caractère spécial');
}
  // ── Login ─────────────────────────────────────────────────────────────
  onLogin() {
    this.errorMsg = '';
    if (!this.form.email || !this.form.password) {
      this.errorMsg = 'Veuillez remplir tous les champs.';
      return;
    }

    this.loading = true;
    this.clientAuth.login(this.form.email, this.form.password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/client/demande']); // ✅
      },
      error: (err: any) => {
        this.errorMsg = err.error?.message || 'Compte introuvable.';
        this.loading = false;
      }
    });
  }
  ngAfterViewInit() {
    if (typeof window !== 'undefined') {
      this.initGoogleButton();
    }
  }

  // ── Register ──────────────────────────────────────────────────────────
  onRegister() {
    this.errorMsg = '';

    if (!this.form.nom || !this.form.prenom ||
        !this.form.email || !this.form.password) {
      this.errorMsg = 'Veuillez remplir tous les champs.';
      return;
    }

  const pwdError = validatePassword(this.form.password);
  if (pwdError) {
    this.errorMsg = pwdError;
    return;
  }


    if (this.form.password !== this.form.confirmPassword) {
      this.errorMsg = 'Les mots de passe ne correspondent pas.';
      return;
    }

    this.loading = true;
    this.clientAuth.register(
      this.form.email,
      this.form.password,
      this.form.nom,
      this.form.prenom
    ).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/client/demande']); // ✅
      },
    error: (err: any) => {
  const msg = err.error?.message || '';

  if (msg.includes('Email déjà utilisé')) {
    this.errorMsg = '📧 Cet email est déjà associé à un compte.';
  } else if (msg.includes('8 caractères')) {
    this.errorMsg = '🔒 Le mot de passe doit contenir au moins 8 caractères.';
  } else if (msg.includes('majuscule')) {
    this.errorMsg = '🔒 Le mot de passe doit contenir au moins une majuscule.';
  } else if (msg.includes('chiffre')) {
    this.errorMsg = '🔒 Le mot de passe doit contenir au moins un chiffre.';
  } else if (msg.includes('spécial')) {
    this.errorMsg = '🔒 Le mot de passe doit contenir au moins un caractère spécial.';
  } else {
    this.errorMsg = 'Une erreur est survenue. Veuillez réessayer.';
  }

  this.loading = false;
}
    });
  }

  // ── Google OAuth ───────────────────────────────────────────────────────
  initGoogleButton() {
    const interval = setInterval(() => {
      if (typeof google !== 'undefined') {
        clearInterval(interval);
        google.accounts.id.initialize({
          client_id: 'VOTRE_GOOGLE_CLIENT_ID',//
          callback: (response: any) => this.handleGoogleResponse(response)
        });
        google.accounts.id.renderButton(
          document.getElementById('google-btn'),
          { theme: 'outline', size: 'large', width: '100%', locale: 'fr',  text: "Se connecter avec Google" }
        );
      }
    }, 200);
  }

  handleGoogleResponse(response: any) {
    this.loading = true;
    this.errorMsg = '';
    this.clientAuth.loginWithGoogle(response.credential).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/client/demande']); // ✅
      },
      error: () => {
        this.errorMsg = 'Erreur de connexion Google.';
        this.loading = false;
      }
    });
  }

}
