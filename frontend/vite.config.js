import { fileURLToPath, URL } from 'node:url'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

const src = (path) => fileURLToPath(new URL(`./src/${path}`, import.meta.url))

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: [
      // The UI was ported from a Next.js app; these shims let its components
      // keep their Next/Clerk-for-Next imports unchanged under Vite.
      { find: /^next\/link$/, replacement: src('shims/next-link.tsx') },
      { find: /^next\/image$/, replacement: src('shims/next-image.tsx') },
      { find: /^next\/navigation$/, replacement: src('shims/next-navigation.ts') },
      { find: /^@clerk\/nextjs$/, replacement: src('shims/clerk-nextjs.tsx') },
      { find: /^@\//, replacement: src('') },
    ],
  },
})
