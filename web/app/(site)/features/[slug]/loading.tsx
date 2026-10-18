export default function FeatureDetailLoading() {
  return (
    <div className="subpage feature-loading" aria-live="polite" aria-busy="true">
      <div className="feature-loading-card">
        <span className="feature-loading-spinner" aria-hidden="true" />
        <span>LOADING SOURCE</span>
      </div>
    </div>
  )
}
