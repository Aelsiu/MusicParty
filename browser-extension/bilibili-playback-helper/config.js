export const SUPPORTED_ORIGINS = Object.freeze(['http://101.132.17.83']);
export const RULES_REVISION = '26101101-g0-1';
// The domain-only candidate cannot distinguish a same-host cross-origin frame.
// Do not change this gate using a successful handshake or CDN 206.
export const G0_APPROVED = false;
