type KpiCardProps = {
  icon: string;
  iconTone?: 'health' | 'sse' | 'events' | 'neutral';
  label: string;
  value: string;
  tone?: 'default' | 'success' | 'warn';
};

export default function KpiCard(props: KpiCardProps) {
  return (
    <article className={`card kpi-card ${props.tone ?? 'default'}`}>
      <p className="meta kpi-label">
        <span className={`kpi-icon ${props.iconTone ?? 'neutral'}`} aria-hidden="true">{props.icon}</span>
        {props.label}
      </p>
      <p className="kpi-value">{props.value}</p>
    </article>
  );
}
