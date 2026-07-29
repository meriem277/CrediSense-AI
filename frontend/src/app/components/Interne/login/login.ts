import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../../services/Interne/auth.service';
import { AuthResponse, LoginRequest } from '../../../models/auth.model';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule, CommonModule, RouterModule, FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {

  form: FormGroup;
  loading     = false;
  error       = '';
  successMsg  = '';        // ✅
  forgotEmail = '';        // ✅

  mode:      'login' | 'forgot' = 'login';
  loginMode: 'agent' | 'admin'  = 'agent';  // ✅

  constructor(
    private fb:     FormBuilder,
    private http:   HttpClient,
    private auth:   AuthService,
    private router: Router
  ) {
    this.form = this.fb.group({
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(6)]]
    });
  }

  // ── Login ──────────────────────────────────────────────────────────
  submit() {
    if (this.form.invalid) return;
    this.loading = true;
    this.error   = '';

    const body: LoginRequest = this.form.value;

    this.http.post<AuthResponse>('http://localhost:8081/api/auth/login', body)
      .subscribe({
        next: (res) => {
          this.loading = false;
          this.auth.login(res.token, { id: res.id, role: res.role });
          if (res.role === 'ADMIN') {
            this.router.navigate(['/admin-dashboard']);
          } else {
            this.router.navigate(['/dashboard']);
          }
        },
        error: (err) => {
          this.error   = err.error?.message || 'Compte introuvable';
          this.loading = false;
        }
      });
  }

  // ── Forgot password ────────────────────────────────────────────────
  onForgotPassword() {                          // ✅
    this.error      = '';
    this.successMsg = '';

    if (!this.forgotEmail) {
      this.error = 'Veuillez entrer votre email.';
      return;
    }

    this.loading = true;
    this.http.post(
      'http://localhost:8081/api/auth/forgot-password',
      { email: this.forgotEmail }
    ).subscribe({
      next: () => {
        this.loading    = false;
        this.successMsg = '📧 Email envoyé ! Vérifiez votre boîte mail.';
      },
      error: (err: any) => {
        this.error   = err.error?.message || 'Erreur lors de l\'envoi.';
        this.loading = false;
      }
    });
  }
}
