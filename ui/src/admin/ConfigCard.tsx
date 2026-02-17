import type { CatalogDefaults } from '../models';

type ConfigCardProps = {
  catalogDefaults: CatalogDefaults;
  configView: Record<string, unknown>;
};

export default function ConfigCard(props: ConfigCardProps) {
  return (
    <section className="card config-card">
      <h2 className="section-title">Runtime / Config</h2>
      <p className="meta section-description">Read-only runtime overview. Raw JSON is available for deep diagnostics.</p>
      <p className="meta section-helper">System summary</p>
      <ul className="config-summary">
        <li>Default ZIPs: {props.catalogDefaults.defaultZipCodes.length}</li>
        <li>Default watchlist symbols: {props.catalogDefaults.defaultWatchlist.length}</li>
        <li>Default news sources: {props.catalogDefaults.defaultNewsSources.length}</li>
        <li>Config sections: {Object.keys(props.configView ?? {}).length}</li>
      </ul>
      <details className="admin-collapsible">
        <summary><span className="caret" aria-hidden="true">▶</span> View raw JSON</summary>
        <pre>{JSON.stringify(props.configView, null, 2)}</pre>
      </details>
    </section>
  );
}
