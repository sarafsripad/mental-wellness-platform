// Local test build only (synthetic data). The emulator reaches the host's stack through
// `adb reverse tcp:8080 tcp:8080` and `adb reverse tcp:8081 tcp:8081`, so token issuers stay localhost.
export const API = 'http://localhost:8080';
export const KEYCLOAK_ISSUER = 'http://localhost:8081/realms/wellness-local';
export const CLIENT_ID = 'wellness-mobile';
export const REDIRECT_URL = 'org.mentalwellness.app:/oauth2redirect';
