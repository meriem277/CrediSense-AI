import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { AuthResponse, RegisterRequest } from '../../models/auth.model';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, CommonModule, RouterModule],
  templateUrl: './register.html',
  styleUrl: './register.scss' ,
  standalone: true
})
export class Register {
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
      nom:      ['', Validators.required],
      email:    ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(6)]],
      role:     ['AGENT', Validators.required]
    });
  }
submit() {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.loading = true;
    this.error = '';

    const body: RegisterRequest = {
      nom:      this.form.value.nom,
      email:    this.form.value.email,
      password: this.form.value.password,
      role:     this.form.value.role.toUpperCase() // RoleType.valueOf() attend des majuscules
    };

    this.http.post<AuthResponse>('http://localhost:8081/api/auth/register', body)
      .subscribe({
        next: (res) => {
          this.auth.login(res.token, {
            role:  res.role
          });
          // auth.login() redirige automatiquement vers /dashboard
        },
        error: (err) => {
          if (err.status === 0) {
            this.error = 'Serveur injoignable. Vérifiez que le backend tourne sur le port 8081.';
          } else {
            this.error = err.error?.message || "Erreur lors de l'inscription";
          }
          this.loading = false;
        }
      });
  }

}
