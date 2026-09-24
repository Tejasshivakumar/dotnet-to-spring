// Mirrors com.portway.app.api.Dtos. Kept by hand and small on purpose.

export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type CompileStatus = 'PASS' | 'FAIL' | 'NOT_RUN'
export type ReviewStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'
export type Strategy = 'RULE_MAPPED' | 'AI_VERIFIED' | 'AI_REPAIRED' | 'MANUAL_REQUIRED'
export type Severity = 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'
export type Category = 'CONTROLLER' | 'ENTITY' | 'REPOSITORY' | 'SERVICE' | 'DTO' | 'CONFIG' | 'MODEL' | 'BUILD'

export interface JobSummary {
  id: string
  name: string
  status: JobStatus
  currentStage: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  compileStatus: CompileStatus
  verifier: string | null
  errorMessage: string | null
  files: number | null
  methods: number | null
  methodsByStrategy: Partial<Record<Strategy, number>> | null
}

export interface StageTiming {
  stage: string
  startedAt: string
  completedAt: string | null
  millis: number | null
  detail: string | null
}

export interface JobDetail {
  job: JobSummary
  options: { basePackage: string | null; keepInterfacePrefix: boolean; ai: boolean }
  stats: Record<string, unknown>
  stages: StageTiming[]
}

export interface FileSummary {
  id: string
  path: string
  category: Category
  strategy: Strategy
  confidence: number
  compileStatus: CompileStatus
  reviewStatus: ReviewStatus
  edited: boolean
  sourcePath: string | null
  findings: number
  highestSeverity: Severity | null
}

export interface FindingView {
  id: string
  fileId: string | null
  severity: Severity
  code: string
  message: string
  sourcePath: string | null
  sourceLine: number | null
  generatedLine: number | null
}

export interface MethodView {
  key: string
  javaSignature: string
  strategy: Strategy
  tier: 'A' | 'B' | 'C' | null
  reason: string | null
  sourcePath: string | null
  sourceLine: number | null
  sourceEndLine: number | null
}

export interface FileDetail {
  file: FileSummary
  language: string
  content: string
  originalContent: string
  sourceLanguage: string | null
  sourceContent: string | null
  findings: FindingView[]
  methods: MethodView[]
}

export interface Report {
  job: JobSummary
  stats: Record<string, unknown>
  findings: { code: string; severity: Severity; count: number; findings: FindingView[] }[]
  manualItems: {
    javaSignature: string
    reason: string
    sourcePath: string | null
    sourceLine: number | null
    fileId: string | null
    path: string | null
  }[]
  review: { accepted: number; rejected: number; pending: number }
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface Problem {
  title?: string
  detail?: string
  status?: number
}
