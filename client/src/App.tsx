import './index.css'
import { MainView } from './views/MainView'
import { useEffect, useState } from 'react'
import { useConnectionStore } from './stores/connectionStore'
import { useLocalFileStore } from './stores/localFileStore'
import { bootstrapLocalRuntime, useRuntimeStore } from './stores/runtimeStore'
import { ConnectionStatus } from './types'
import { ErrorBoundary } from './components/ErrorBoundary'
import { BootScreen } from './components/BootScreen'

function App() {
  const { startHeartbeat, stopHeartbeat, connections, disconnect } = useConnectionStore()

  // Restore the last opened local folder
  const restoreLocalFolder = useLocalFileStore((s) => s.restoreFolder)
  const rootPath = useLocalFileStore((s) => s.rootPath)

  // Local Agent boot screen: full-screen progress / failure while not ready
  const runtimeStatus = useRuntimeStore((s) => s.status)
  const runtimeReady = runtimeStatus.running && runtimeStatus.port > 0 && !!runtimeStatus.token
  const [bootDismissed, setBootDismissed] = useState(false)

  useEffect(() => {
    // On app start: reset all connections to disconnected (avoid backend/frontend drift)
    useConnectionStore.setState((state) => ({
      connections: state.connections.map((c) => ({ ...c, status: ConnectionStatus.DISCONNECTED })),
    }))
    // Start heartbeat
    startHeartbeat()
    let cancelled = false
    const bootstrap = async () => {
      // Restore the local folder silently (no dialog), then pass it as the Agent workspace
      await restoreLocalFolder()
      if (!cancelled) {
        void bootstrapLocalRuntime(useLocalFileStore.getState().rootPath ?? undefined)
      }
    }
    void bootstrap()
    return () => {
      cancelled = true
      stopHeartbeat()
    }
  }, [startHeartbeat, stopHeartbeat, restoreLocalFolder])

  // Keep the Agent workspace in sync when the local folder changes
  useEffect(() => {
    void bootstrapLocalRuntime(rootPath ?? undefined)
  }, [rootPath])

  // Disconnect all SSH sessions when the app closes
  useEffect(() => {
    const handleBeforeUnload = () => {
      connections
        .filter((c) => c.status === ConnectionStatus.CONNECTED)
        .forEach((c) => disconnect(c.id))
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [connections, disconnect])

  const handleBootRetry = () => {
    setBootDismissed(false)
    void bootstrapLocalRuntime(useLocalFileStore.getState().rootPath ?? undefined)
  }

  return (
    <ErrorBoundary>
      <MainView />
      {!runtimeReady && !bootDismissed && (
        <BootScreen onRetry={handleBootRetry} onSkip={() => setBootDismissed(true)} />
      )}
    </ErrorBoundary>
  )
}

export default App
