import type { FileSummary } from '../api/types'

function file(overrides: Partial<FileSummary>): FileSummary {
  return {
    id: overrides.path ?? 'id',
    path: 'src/main/java/app/X.java',
    category: 'MODEL',
    strategy: 'RULE_MAPPED',
    confidence: 0.95,
    compileStatus: 'PASS',
    reviewStatus: 'PENDING',
    edited: false,
    sourcePath: null,
    findings: 0,
    highestSeverity: null,
    ...overrides,
  }
}

export const FILES: FileSummary[] = [
  file({ id: 'pom', path: 'pom.xml', category: 'BUILD', compileStatus: 'NOT_RUN' }),
  file({ id: 'book', path: 'src/main/java/app/domain/Book.java', category: 'ENTITY', findings: 2, highestSeverity: 'LOW' }),
  file({ id: 'svc', path: 'src/main/java/app/service/BookServiceImpl.java', category: 'SERVICE', strategy: 'MANUAL_REQUIRED', confidence: 0, findings: 3, highestSeverity: 'HIGH', reviewStatus: 'ACCEPTED' }),
  file({ id: 'ctl', path: 'src/main/java/app/controller/BooksController.java', category: 'CONTROLLER', compileStatus: 'FAIL', findings: 1 }),
]
