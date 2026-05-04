import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';
import { adminGuard } from './guards/role.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./components/login/login').then(m => m.Login) },
  //{ path: 'dashboard', loadComponent: () => import('./components/dashboard/dashboard.component').then(m => m.DashboardComponent), canActivate: [authGuard] },
  { path: 'admin/register', loadComponent: () => import('./components/register/register').then(m => m.Register), canActivate: [authGuard, adminGuard] },
  { path: '', redirectTo: 'login', pathMatch: 'full' }
];
