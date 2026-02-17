import type { CollectorStatus } from '../models';

type CollectorStatusCardProps = {
  collectorStatus: Record<string, CollectorStatus>;
};

export default function CollectorStatusCard(props: CollectorStatusCardProps) {
  const rows = Object.entries(props.collectorStatus);

  return (
    <section className="card collector-card">
      <h2 className="section-title">Collectors</h2>
      <p className="meta section-description">Current collector runtime health and recent outcomes.</p>
      {rows.length === 0 ? <p className="empty">No collector status yet.</p> : (
        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Last Run</th>
                <th>Duration</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(([name, status]) => (
                <tr key={name} className="collector-row">
                  <td>
                    <strong>{name}</strong>
                    {status.lastErrorMessage && (
                      <details className="collector-error">
                        <summary>Last error</summary>
                        <p className="meta">{status.lastErrorMessage}</p>
                      </details>
                    )}
                  </td>
                  <td>{formatInstant(status.lastRunAt)}</td>
                  <td>{status.lastDurationMillis ?? '-'}ms</td>
                  <td>
                    <span className={`pill ${status.lastSuccess ? 'success' : 'error'}`}>
                      {status.lastSuccess ? 'Healthy' : 'Failed'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return '-';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
