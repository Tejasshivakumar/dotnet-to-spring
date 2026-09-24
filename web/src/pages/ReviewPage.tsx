import { DiffEditor, Editor, type BeforeMount, type DiffOnMount, type OnMount } from '@monaco-editor/react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useFile, useFiles, useJob, useReview, useVerify } from '../api/hooks'
import type { FileDetail, FindingView, Severity } from '../api/types'
import { CompileBadge, ConfidenceBadge, SeverityChip } from '../components/Badges'
import { FileTree } from '../components/FileTree'
import { Button, ErrorNote, Layout } from '../components/Layout'
import { ReviewActions } from '../components/ReviewActions'
import { groupByCategory, SEVERITY_ORDER } from '../lib/files'

type Editors = {
  generated?: Parameters<OnMount>[0]
  source?: Parameters<OnMount>[0]
}

function useMonacoTheme() {
  const query = '(prefers-color-scheme: dark)'
  const [dark, setDark] = useState(() => window.matchMedia?.(query).matches ?? false)
  useEffect(() => {
    const media = window.matchMedia?.(query)
    const listener = (e: MediaQueryListEvent) => setDark(e.matches)
    media?.addEventListener('change', listener)
    return () => media?.removeEventListener('change', listener)
  }, [])
  return dark ? 'portway-dark' : 'portway-light'
}

/**
 * C# and Java never line up, so a diff's red and green blocks would mark every line and hide
 * the code. The two sides are shown aligned but uncoloured: this is a side-by-side reading
 * view, not a patch.
 */
const defineThemes: BeforeMount = (monaco) => {
  const quiet = {
    'diffEditor.insertedTextBackground': '#00000000',
    'diffEditor.removedTextBackground': '#00000000',
    'diffEditor.insertedLineBackground': '#00000000',
    'diffEditor.removedLineBackground': '#00000000',
    'diffEditor.diagonalFill': '#00000000',
    'diffEditorGutter.insertedLineBackground': '#00000000',
    'diffEditorGutter.removedLineBackground': '#00000000',
  }
  monaco.editor.defineTheme('portway-light', { base: 'vs', inherit: true, rules: [], colors: quiet })
  monaco.editor.defineTheme('portway-dark', { base: 'vs-dark', inherit: true, rules: [], colors: { ...quiet, 'editor.background': '#1a1a19' } })
}

export function ReviewPage() {
  const { id = '', fileId } = useParams()
  const navigate = useNavigate()
  const job = useJob(id)
  const files = useFiles(id)
  const verify = useVerify(id)

  // Without a file in the URL, open the first file in reading order: controllers first.
  const selected = fileId ?? (files.data ? groupByCategory(files.data)[0]?.files[0]?.id : undefined)
  const detail = useFile(id, selected)

  const limited = job.data?.job.verifier?.includes('limited')
  return (
    <Layout jobId={id}>
      <div className="flex h-[calc(100vh-3.5rem)] flex-col">
        {limited && (
          <div className="border-b border-line bg-surface-2 px-4 py-1.5 text-xs text-ink-2">
            <span aria-hidden className="mr-1 text-warning">!</span>
            Verified with a limited classpath (in-process compiler). A full Maven build can still catch issues this does
            not, such as a dependency missing from the pom.
          </div>
        )}
        <div className="flex min-h-0 flex-1">
          <aside className="w-[340px] shrink-0 border-r border-line bg-surface">
            <div className="flex items-center justify-between border-b border-line px-3 py-2">
              <span className="text-sm font-medium">{job.data?.job.name}</span>
              <Button onClick={() => verify.mutate()} disabled={verify.isPending} title="Compile the files as reviewed">
                {verify.isPending ? 'Queued…' : '↻ Re-verify'}
              </Button>
            </div>
            <div className="h-[calc(100%-45px)]">
              {files.data && (
                <FileTree files={files.data} selectedId={selected} onSelect={(f) => navigate(`/jobs/${id}/review/${f}`)} />
              )}
            </div>
          </aside>
          <section className="flex min-w-0 flex-1 flex-col">
            <ErrorNote error={files.error ?? detail.error} />
            {detail.data && <FilePane key={detail.data.file.id} jobId={id} detail={detail.data} />}
          </section>
        </div>
      </div>
    </Layout>
  )
}

function FilePane({ jobId, detail }: { jobId: string; detail: FileDetail }) {
  const theme = useMonacoTheme()
  const review = useReview(jobId)
  const editors = useRef<Editors>({})
  const [dirty, setDirty] = useState(false)
  const file = detail.file

  const currentText = () => editors.current.generated?.getValue() ?? detail.content

  const trackEdits = (editor: Parameters<OnMount>[0]) => {
    editors.current.generated = editor
    editor.onDidChangeModelContent(() => setDirty(editor.getValue() !== detail.content))
  }

  const onDiffMount: DiffOnMount = (diff) => {
    trackEdits(diff.getModifiedEditor())
    editors.current.source = diff.getOriginalEditor()
  }

  const reveal = (finding: FindingView) => {
    const target = finding.generatedLine ? editors.current.generated : editors.current.source
    const line = finding.generatedLine ?? finding.sourceLine
    if (target && line) {
      target.revealLineInCenter(line)
      target.setPosition({ lineNumber: line, column: 1 })
      target.focus()
    }
  }

  const options = {
    fontSize: 13,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    renderSideBySide: true,
    originalEditable: false,
    readOnly: false,
    wordWrap: 'off' as const,
  }

  const sorted = [...detail.findings].sort(
    (a, b) => SEVERITY_ORDER.indexOf(a.severity as Severity) - SEVERITY_ORDER.indexOf(b.severity as Severity),
  )

  return (
    <>
      <header className="flex flex-wrap items-center gap-3 border-b border-line bg-surface px-4 py-2">
        <div className="min-w-0 flex-1">
          <p className="truncate font-mono text-[13px]">{file.path}</p>
          <p className="text-xs text-ink-3">
            {file.sourcePath ? <>from <span className="font-mono">{file.sourcePath}</span></> : 'no single C# source'}
            {file.edited && ' · edited'}
          </p>
        </div>
        <CompileBadge status={file.compileStatus} />
        <ConfidenceBadge confidence={file.confidence} />
        <ReviewActions
          status={file.reviewStatus}
          dirty={dirty}
          busy={review.isPending}
          onRevert={() => {
            editors.current.generated?.setValue(detail.content)
            setDirty(false)
          }}
          onReview={(status, saveEdit) =>
            review.mutate(
              { fileId: file.id, status, editedContent: saveEdit ? currentText() : undefined },
              { onSuccess: () => setDirty(false) },
            )
          }
        />
      </header>
      <ErrorNote error={review.error} />

      <div className="min-h-0 flex-1">
        {detail.sourceContent ? (
          <DiffEditor
            original={detail.sourceContent}
            modified={detail.content}
            originalLanguage={detail.sourceLanguage ?? 'plaintext'}
            modifiedLanguage={detail.language}
            theme={theme}
            options={{ ...options, renderIndicators: false, renderOverviewRuler: false }}
            beforeMount={defineThemes}
            onMount={onDiffMount}
          />
        ) : (
          <Editor
            value={detail.content}
            language={detail.language}
            theme={theme}
            options={options}
            beforeMount={defineThemes}
            onMount={trackEdits}
          />
        )}
      </div>

      <div className="max-h-[32%] overflow-y-auto border-t border-line bg-surface">
        <div className="flex items-baseline gap-2 px-4 pt-3">
          <h2 className="text-sm font-semibold">Findings</h2>
          <span className="text-xs text-ink-3">{sorted.length} · click to jump to the line</span>
        </div>
        {sorted.length === 0 ? (
          <p className="px-4 pb-3 pt-1 text-sm text-ink-3">None for this file.</p>
        ) : (
          <ul className="px-2 pb-2 pt-1">
            {sorted.map((f) => (
              <li key={f.id}>
                <button
                  onClick={() => reveal(f)}
                  className="flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-2"
                >
                  <SeverityChip severity={f.severity} />
                  <span className="shrink-0 font-mono text-[11px] text-ink-3">{f.code}</span>
                  <span className="min-w-0 flex-1 text-ink">{f.message}</span>
                  {(f.generatedLine ?? f.sourceLine) && (
                    <span className="shrink-0 text-xs tabular-nums text-ink-3">
                      {f.generatedLine ? `Java:${f.generatedLine}` : `C#:${f.sourceLine}`}
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
        {detail.methods.length > 0 && (
          <details className="border-t border-line px-4 py-2">
            <summary className="cursor-pointer text-sm font-semibold">Methods ({detail.methods.length})</summary>
            <ul className="mt-2 grid gap-1 pb-1">
              {detail.methods.map((m) => (
                <li key={m.key} className="flex items-start gap-2 text-sm">
                  <span className="w-28 shrink-0 text-[11px] font-medium text-ink-2">{m.strategy.replace('_', ' ').toLowerCase()}</span>
                  <span className="min-w-0 flex-1">
                    <span className="font-mono text-[12px]">{m.javaSignature}</span>
                    {m.reason && <span className="block text-xs text-ink-3">{m.reason}</span>}
                  </span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
    </>
  )
}
