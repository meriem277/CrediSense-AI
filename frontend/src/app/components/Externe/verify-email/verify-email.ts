import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ClientAuthService } from '../../../services/Externe/Client-auth.service';

@Component({
  selector: 'app-verify-email',
  imports: [CommonModule, RouterModule],
  templateUrl: './verify-email.html',
  styleUrl: './verify-email.scss',
})
export class VerifyEmail  implements OnInit {

  status: 'loading' | 'success' | 'error' = 'loading';
  errorMsg = '';

  constructor(
    private route: ActivatedRoute,
    private clientAuth: ClientAuthService
  ) {}

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');

    if (!token) {
      this.status = 'error';
      this.errorMsg = 'Lien de vérification invalide.';
      return;
    }

    this.clientAuth.verifyEmail(token).subscribe({
      next: () => {
        this.status = 'success';
      },
      error: (err: any) => {
        this.status = 'error';
        this.errorMsg = err.error?.message || 'Lien expiré ou invalide.';
      }
    });}
}
