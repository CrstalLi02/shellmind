import Editor from '@monaco-editor/react'

export function CodeEditor() {
  return (
    <div className="flex-1 flex flex-col bg-[#1e1e1e]">
      {/* editor title bar */}
      <div className="h-9 bg-[#252526] border-b border-[#3c3c3c] flex items-center px-2">
        <div className="px-3 py-1 text-sm text-white bg-[#1e1e1e] border-t-2 border-[#007acc] rounded-t">
          editor.js
        </div>
      </div>

      {/* Monaco Editor */}
      <div className="flex-1">
        <Editor
          height="100%"
          defaultLanguage="javascript"
          theme="vs-dark"
          defaultValue={`// ShellMind Code Editor
// use AI Agent co- help edit write code

function hello() {
  console.log("Hello from ShellMind!");
}
`}
          options={{
            fontSize: 14,
            fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
            minimap: { enabled: true },
            scrollBeyondLastLine: false,
            automaticLayout: true,
            tabSize: 2,
            wordWrap: 'on',
            lineNumbers: 'on',
            renderWhitespace: 'selection',
            padding: { top: 10 },
          }}
        />
      </div>
    </div>
  )
}
