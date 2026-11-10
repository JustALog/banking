import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const gateway = process.env.GATEWAY_URL || 'http://localhost:8080';
// The gateway has no CORS config, so the browser talks to Vite and Vite forwards the API calls.
// Page routes live under /app, /admin, /login, /register, so they never collide with these prefixes.
const proxy = Object.fromEntries(['/auth', '/accounts', '/transfers', '/notifications'].map((p) => [p, gateway]));

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 5173, proxy },
});
