// PKCE login against Keycloak (client wellness-mobile). Tokens are kept in memory only, never stored:
// the PIN unlocks offline work, and a fresh login is needed only to sync (ADR-017).
import {authorize, refresh, type AuthConfiguration} from 'react-native-app-auth';
import {CLIENT_ID, KEYCLOAK_ISSUER, REDIRECT_URL} from './config';
import {native} from './native';
import {SessionError} from './outbox';

const config: AuthConfiguration = {
  issuer: KEYCLOAK_ISSUER,
  clientId: CLIENT_ID,
  redirectUrl: REDIRECT_URL,
  scopes: ['openid'],
  usePKCE: true,
  // The local Keycloak speaks plain HTTP on localhost. Debug builds only.
  dangerouslyAllowInsecureHttpRequests: __DEV__,
};

export type Auth = {token: string; refresh: string | null; exp: number; subject: string; roles: string[]; owner: string};

declare function atob(data: string): string; // provided by React Native's runtime

function claims(jwt: string): any {
  const part = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part + '='.repeat((4 - (part.length % 4)) % 4)));
}

async function fromTokens(accessToken: string, refreshToken: string | null): Promise<Auth> {
  const c = claims(accessToken);
  return {token: accessToken, refresh: refreshToken, exp: c.exp * 1000, subject: c.sub,
    roles: (c.realm_access && c.realm_access.roles) || [], owner: await native.ownerOf(c.sub)};
}

export async function login(): Promise<Auth> {
  const r = await authorize(config);
  return fromTokens(r.accessToken, r.refreshToken);
}

/** Refreshes 30 s before expiry. Throws SessionError when a new login is needed. */
export async function fresh(auth: Auth): Promise<Auth> {
  if (auth.exp - Date.now() > 30000) return auth;
  if (!auth.refresh) throw new SessionError('session expired');
  try {
    const r = await refresh(config, {refreshToken: auth.refresh});
    return fromTokens(r.accessToken, r.refreshToken || auth.refresh);
  } catch {
    throw new SessionError('session expired');
  }
}
