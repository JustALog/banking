import { defineConfig } from 'vite';

const gateway = process.env.GATEWAY_URL || 'http://localhost:8080';

// The gateway has no CORS config, so the browser talks to Vite and Vite forwards the API calls.
export default defineConfig({
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      ['/auth', '/accounts', '/transfers', '/notifications'].map((path) => [path, gateway]),
    ),
  },
});
