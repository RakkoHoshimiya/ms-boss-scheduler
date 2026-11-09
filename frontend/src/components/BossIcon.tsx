/** BOSS 小头像：以 public/boss-icons/{iconKey}.png 为图，缺图时回退成 🐉。 */
export default function BossIcon({
  iconKey,
  name,
  size = 26,
}: {
  iconKey?: string | null;
  name?: string;
  size?: number;
}) {
  if (!iconKey) {
    return (
      <span className="boss-icon fallback" style={{ width: size, height: size }} title={name}>
        🐉
      </span>
    );
  }
  return (
    <img
      className="boss-icon"
      src={`${import.meta.env.BASE_URL}boss-icons/${iconKey}.png`}
      alt={name ?? iconKey}
      title={name}
      style={{ width: size, height: size }}
      loading="lazy"
    />
  );
}
