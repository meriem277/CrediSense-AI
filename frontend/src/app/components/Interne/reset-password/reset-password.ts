import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './reset-password.html',
  styleUrl: './reset-password.scss'
})
export class ResetPassword implements OnInit {

  token        = '';
  password     = '';
  confirmPassword = '';
  loading      = false;
  errorMsg     = '';
  successMsg   = '';
  tokenInvalid = false;

  passwordStrength = { percent: 0, color: '#e2e8f0', label: '' };

  constructor(
    private route:  ActivatedRoute,
    private router: Router,
    private http:   HttpClient
  ) {}

  ngOnInit() {
    this.token = this.route.snapshot.queryParamMap.get('token') || '';
    if (!this.token) this.tokenInvalid = true;
  }

  checkPassword(value: string) {
    let score = 0;
    if (value.length >= 8)     score++;
    if (/[A-Z]/.test(value))   score++;
    if (/[0-9]/.test(value))   score++;
    if (/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(value)) score++;
    if (value.length >= 12)    score++;

    switch (score) {
      case 0:
      case 1: this.passwordStrength = { percent: 20,  color: '#dc2626', label: 'Très faible' }; break;
      case 2: this.passwordStrength = { percent: 40,  color: '#f97316', label: 'Faible'      }; break;
      case 3: this.passwordStrength = { percent: 60,  color: '#eab308', label: 'Moyen'       }; break;
      case 4: this.passwordStrength = { percent: 80,  color: '#22c55e', label: 'Fort'        }; break;
      case 5: this.passwordStrength = { percent: 100, color: '#16a34a', label: 'Très fort'   }; break;
    }
  }

  onSubmit() {
    this.errorMsg   = '';
    this.successMsg = '';

    if (!this.password || !this.confirmPassword) {
      this.errorMsg = 'Veuillez remplir tous les champs.';
      return;
    }

    if (this.password !== this.confirmPassword) {
      this.errorMsg = 'Les mots de passe ne correspondent pas.';
      return;
    }

    this.loading = true;
    this.http.post('http://localhost:8081/api/auth/reset-password',
      { token: this.token, password: this.password }
    ).subscribe({
      next: () => {
        this.loading    = false;
        this.successMsg = '✅ Mot de passe réinitialisé avec succès !';
        setTimeout(() => this.router.navigate(['/login']), 2000);
      },
      error: (err: any) => {
        this.errorMsg = err.error?.message || 'Lien expiré ou invalide.';
        this.loading  = false;
      }
    });
  }
}
