import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/Interne/auth.service';

export const authGuard: CanActivateFn = () => {
   const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn()) return true;
  return router.createUrlTree(['/login']);
};
export const agentGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const user = auth.getUser();
  console.log('agentGuard — user:', user);  // ← ajouter
  if (user?.role === 'AGENT') return true;
  return router.createUrlTree(['/admin-dashboard']);
};

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const user = auth.getUser();
  // ← gérer les deux formats
  if (user?.role === 'ADMIN' || user?.role === 'ROLE_ADMIN') return true;
  return router.createUrlTree(['/dashboard']);
};
