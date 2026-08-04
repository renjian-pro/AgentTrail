import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles.css'

const app = createApp(App).use(createPinia()).use(router)
// Wait for the guard's initial redirect (e.g. to /login) to resolve before mounting —
// otherwise App.vue can briefly mount against the pre-redirect route.
void router.isReady().then(() => app.mount('#app'))
