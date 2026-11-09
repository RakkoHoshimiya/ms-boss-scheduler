import { CHANGE_LOG, CHANGE_TYPE_LABEL, type ChangeType } from '../data/changelog';

/** 分类对应的颜色 / 图标，和全局的 tag 样式复用。 */
const TYPE_CLASS: Record<ChangeType, string> = {
  feature: 'cl-feature',
  fix: 'cl-fix',
  improve: 'cl-improve',
  security: 'cl-security',
};

/**
 * 更新记录：把每次迭代整理成一句话，方便群友了解系统有哪些变化。
 * 未登录也能看 —— 对新来的群友来说，这一页正好能快速了解系统能力。
 */
export default function ChangelogPage() {
  const total = CHANGE_LOG.reduce((sum, group) => sum + group.entries.length, 0);

  return (
    <div>
      <div className="page-head">
        <h1>更新记录</h1>
        <span className="sub">
          系统每次改动都记在这里，一条一句话 · 共 {CHANGE_LOG.length} 个阶段、{total} 条更新
        </span>
      </div>

      <div className="changelog">
        {CHANGE_LOG.map((group, index) => (
          <section className="cl-group" key={group.date + group.title}>
            <div className="cl-marker">
              <span className={`cl-dot ${index === 0 ? 'is-new' : ''}`} />
              {index !== CHANGE_LOG.length - 1 ? <span className="cl-line" /> : null}
            </div>
            <div className="cl-body">
              <header className="cl-head">
                <h2>{group.title}</h2>
                <span className="cl-date">{group.date}</span>
                {index === 0 ? <span className="tag mine-tag">最新</span> : null}
              </header>
              <ul className="cl-list">
                {group.entries.map((entry, i) => (
                  <li key={entry.text + i}>
                    <span className={`cl-type ${TYPE_CLASS[entry.type]}`}>
                      {CHANGE_TYPE_LABEL[entry.type]}
                    </span>
                    <span className="cl-text">{entry.text}</span>
                  </li>
                ))}
              </ul>
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
