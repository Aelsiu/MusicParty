import { defineStore } from 'pinia';
import { ref } from 'vue';
import { STORAGE_KEYS } from '../constants/keys';

export const useAdminStore = defineStore('admin', () => {
    const adminPassword = ref('');
    const showAuthModal = ref(false);
    const showDashboard = ref(false);
    const isVerified = ref(false);

    const setAdminPassword = (pwd) => {
        adminPassword.value = pwd;
        // Raw license keys are never persisted by the browser.
    };

    const logout = () => {
        adminPassword.value = '';
        localStorage.removeItem(STORAGE_KEYS.ADMIN_PASSWORD);
        isVerified.value = false;
        showDashboard.value = false;
    };

    return {
        adminPassword,
        showAuthModal,
        showDashboard,
        isVerified,
        setAdminPassword,
        logout
    };
});
