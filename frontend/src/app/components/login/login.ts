import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { AuthResponse, LoginRequest } from '../../models/auth.model';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-login',
    standalone: true,
  imports: [ReactiveFormsModule, CommonModule, RouterModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
   form: FormGroup;
  loading = false;
  error = '';

  constructor(
    private fb: FormBuilder,
    private http: HttpClient,
    private auth: AuthService,
    private router: Router
  ) {
    this.form = this.fb.group({
      email:    ['', [Validators.required, Validators.email]],
  password: ['', [Validators.required, Validators.minLength(6)]]
    });
  }

submit() {
  if (this.form.invalid) return;
  this.loading = true;
  this.error = '';

  const body: LoginRequest = this.form.value;

  this.http.post<AuthResponse>('http://localhost:8081/api/auth/login', body)
    .subscribe({
      next: (res) => {
        this.loading = false;  // ✅ ajouté
        this.auth.login(res.token, {   id: res.id,  email: res.email, nom: res.nom, role: res.role });
         if (res.role === 'ADMIN') {
          this.router.navigate(['/admin-dashboard']);
        } else {
          this.router.navigate(['/dashboard']);
        }
      },
      error: (err) => {
        this.error = err.error?.message || 'Compte introuvable';  // ✅ message mis à jour
        this.loading = false;
      }
    });
}

}
