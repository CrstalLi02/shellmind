import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import monacoEditorPlugin from 'vite-plugin-monaco-editor'

// Vite plugins may export as ESM default; take .default or the module itself
const monacoPlugin = (monacoEditorPlugin as any).default || monacoEditorPlugin

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    monacoPlugin({
      // Import only the languages you need to reduce bundle size
      // e.g. ['json', 'javascript', 'typescript', 'html', 'css']
      languageWorkers: ['editorWorkerService', 'css', 'html', 'json', 'typescript']
    }),
  ],
  base: './',
  clearScreen: false,
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 1000, // Monaco Editor core is normally >500KB
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            // React core + ReactDOM (must stay in the same chunk to avoid circular deps)
            if (id.includes('/react-dom/') || id.match(/\/react\/(index|cjs)/) || id.includes('/react/')) {
              return 'react-vendor'
            }
            // Monaco editor - split core from React bindings
            if (id.includes('/monaco-editor/')) return 'monaco-core'
            if (id.includes('/@monaco-editor/')) return 'monaco-react'
            // terminal
            if (id.includes('/@xterm/')) return 'xterm-vendor'
            // Markdown rendering - split by subpackage
            if (id.includes('/react-markdown/')) return 'markdown-react'
            if (id.includes('/remark-') || id.includes('/rehype-') || id.includes('/unified/') || id.includes('/unist/') || id.includes('/vfile/') || id.includes('/micromark/') || id.includes('/mdast-')) return 'markdown-parse'
            if (id.includes('/lowlight/') || id.includes('/highlight.js/') || id.includes('/highlight.js-')) return 'highlight-vendor'
            // Tauri API
            if (id.includes('/@tauri-apps/')) return 'tauri-vendor'
            // Zustand + state management
            if (id.includes('/zustand/')) return 'app-state'
            // Other node_modules → leave to Vite's automatic splitting
          }
        },
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    watch: {
      ignored: ["**/src-tauri/**"],
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8091',
        changeOrigin: true,
      },
    },
  },
})
