// Pairing codes keep four positions, each using the 36 ASCII letters/digits.
export const isPairingCode = value => typeof value === 'string' && /^[a-z0-9]{4}$/i.test(value);
export const normalizePairingCode = value => typeof value === 'string' ? value.toLowerCase() : '';
