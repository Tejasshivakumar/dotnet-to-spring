import { Link, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout'
import { ProgressPage } from './pages/ProgressPage'
import { ReportPage } from './pages/ReportPage'
import { ReviewPage } from './pages/ReviewPage'
import { UploadPage } from './pages/UploadPage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<UploadPage />} />
      <Route path="/jobs/:id" element={<ProgressPage />} />
      <Route path="/jobs/:id/review" element={<ReviewPage />} />
      <Route path="/jobs/:id/review/:fileId" element={<ReviewPage />} />
      <Route path="/jobs/:id/report" element={<ReportPage />} />
      <Route
        path="*"
        element={
          <Layout>
            <div className="mx-auto max-w-xl px-4 py-16 text-center">
              <p className="text-lg font-medium">Page not found</p>
              <Link to="/" className="mt-2 inline-block text-accent hover:underline">Start a migration</Link>
            </div>
          </Layout>
        }
      />
    </Routes>
  )
}
