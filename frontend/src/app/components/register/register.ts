import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, CommonModule],
  templateUrl: './register.html',
  styleUrl: './register.scss',
})
export class Register {
[x: string]: any;



  form = new FormGroup({
    nom:      new FormControl('', Validators.required),
    email:    new FormControl('', [Validators.required, Validators.email]),
    password: new FormControl('', [Validators.required, Validators.minLength(6)]),
    role:     new FormControl('AGENT', Validators.required)
  });

  success = '';
  error = '';

  constructor(private authService: AuthService) {}
showPassword: boolean = false;
loading: boolean = false;
  onSubmit(): void {
    if (this.form.invalid) return;
    this.authService.register(this.form.value as any).subscribe({
      next: () => { this.success = 'Compte créé avec succès !'; this.form.reset({ role: 'AGENT' }); },
      error: (e) => { this.error = e.error?.message || 'Erreur lors de la création'; }
    });
  }
}
