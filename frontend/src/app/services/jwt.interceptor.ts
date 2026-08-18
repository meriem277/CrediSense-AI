import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from './Interne/auth.service';
import { ClientAuthService } from './Externe/Client-auth.service';

export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const agentAuth  = inject(AuthService);
  const clientAuth = inject(ClientAuthService);

  // ✅ Routes client → token client
  const isClientRoute = req.url.includes('/api/public/')
                     || req.url.includes('/api/client-auth/')
                     || req.url.includes('/api/clients/historique');

  const token = isClientRoute
    ? clientAuth.getToken()
    : agentAuth.getToken();

  if (token) {
    req = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` }
    });
  }

  return next(req);
};
