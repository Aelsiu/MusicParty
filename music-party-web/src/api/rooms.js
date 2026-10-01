import client from './client';
import { roomSession } from '../services/roomSession';
export const roomsApi = {
    login: key => client.post('/api/rooms/management/session', { key }),
    session: () => client.get('/api/rooms/management/session'),
    list: () => client.get('/api/rooms'),
    join: code => client.post('/api/rooms/join', { code }),
    resume: (id, token) => client.get(`/api/rooms/${id}/admission`, { headers: { 'X-Room-Token': token } }),
    manage: id => client.get(`/api/rooms/${id}/manage`),
    pairing: id => client.get(`/api/rooms/${id}/pairing`, { skipManagementAuth: !roomSession.ownerAccess }),
    setPairingOpen: (id, open) => client.patch(`/api/rooms/${id}/pairing`, { open }),
    connected: id => client.post(`/api/rooms/${id}/connected`),
    create: (name, requestId) => client.post('/api/rooms', { name, requestId }),
    delete: id => client.delete(`/api/rooms/${id}`),
    licenses: () => client.get('/api/rooms/licenses'),
    addLicense: (key, note = '') => client.post('/api/rooms/licenses', { key, note }),
    updateLicense: (id, key) => client.put(`/api/rooms/licenses/${id}`, { key }),
    updateLicenseNote: (id, note) => client.patch(`/api/rooms/licenses/${id}/note`, { note }),
    deleteLicense: id => client.delete(`/api/rooms/licenses/${id}`),
    getSystemConfig: () => client.get('/api/rooms/system-config'),
    updateSystemConfig: config => client.post('/api/rooms/system-config', config),
    qrCreate: () => client.post('/api/admin/netease-login'),
    qrCheck: task => client.get(`/api/admin/netease-login/${task}`),
    qrCancel: task => client.delete(`/api/admin/netease-login/${task}`)
};
