import client from './client';
export const roomsApi = {
    login: key => client.post('/api/rooms/management/session', { key }),
    session: () => client.get('/api/rooms/management/session'),
    list: () => client.get('/api/rooms'),
    join: code => client.post('/api/rooms/join', { code }),
    resume: (id, token) => client.get(`/api/rooms/${id}/admission`, { headers: { 'X-Room-Token': token } }),
    manage: id => client.get(`/api/rooms/${id}/manage`),
    connected: id => client.post(`/api/rooms/${id}/connected`),
    create: (name, requestId) => client.post('/api/rooms', { name, requestId }),
    delete: id => client.delete(`/api/rooms/${id}`),
    licenses: () => client.get('/api/rooms/licenses'),
    addLicense: key => client.post('/api/rooms/licenses', { key }),
    updateLicense: (id, key) => client.put(`/api/rooms/licenses/${id}`, { key }),
    deleteLicense: id => client.delete(`/api/rooms/licenses/${id}`),
    qrCreate: () => client.post('/api/admin/netease-login'),
    qrCheck: task => client.get(`/api/admin/netease-login/${task}`),
    qrCancel: task => client.delete(`/api/admin/netease-login/${task}`)
};
