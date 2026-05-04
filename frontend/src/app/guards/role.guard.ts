import { CanActivateFn , Router} from "@angular/router";
import { AuthService } from "../services/auth.service";
import { inject } from '@angular/core';

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAdmin()) return true;
  router.navigate(['/dashboard']);
  return false;
};
