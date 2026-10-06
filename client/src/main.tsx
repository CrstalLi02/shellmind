// Must run first: migrate localStorage keys (stores read storage at module load)
import './utils/migrateLegacyStorage'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'

import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'

// Point @monaco-editor/react at the locally imported monaco instance so it does not fetch the CDN
loader.config({ monaco })

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
