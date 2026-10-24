import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { CharacterView } from '../api/types';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';
import Modal from '../components/Modal';
import { JOB_PRESETS } from '../components/ui';

/** 我的信息：登录后改自己的显示名称、维护自己的游戏角色（职业 / 战力 / 备注）。 */
export default function MyCharactersPage() {
  const { user, refresh } = useAuth();
  const { notify } = useToast();
  const [characters, setCharacters] = useState<CharacterView[]>([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState<CharacterView | 'new' | null>(null);
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [savingName, setSavingName] = useState(false);

  // 用户信息加载完成后同步输入框
  useEffect(() => {
    setDisplayName(user?.displayName ?? '');
  }, [user?.displayName]);

  const saveDisplayName = async (event: React.FormEvent) => {
    event.preventDefault();
    const next = displayName.trim();
    if (!next) {
      notify('显示名称不能为空', 'error');
      return;
    }
    if (next === user?.displayName) {
      notify('显示名称没有变化');
      return;
    }
    setSavingName(true);
    try {
      await api.updateDisplayName(next);
      await refresh();
      notify(`显示名称已改为「${next}」`);
    } catch (error) {
      notify(error instanceof Error ? error.message : '改名失败', 'error');
      setDisplayName(user?.displayName ?? '');
    } finally {
      setSavingName(false);
    }
  };

  const load = () => {
    if (!user?.playerId) {
      setCharacters([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    api
      .myCharacters()
      .then(setCharacters)
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(load, [user?.playerId]);

  const remove = async (character: CharacterView) => {
    if (!window.confirm(`确认删除角色「${character.job} ${character.power}M」？`)) {
      return;
    }
    try {
      await api.deleteMyCharacter(character.id);
      notify('已删除角色');
      load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  return (
    <div>
      <div className="page-head">
        <h1>我的信息</h1>
        <span className="sub">
          共 {characters.length} 个角色 · 战力单位 M（Million，百万）
        </span>
        <div className="spacer" />
        {user?.playerId ? (
          <button className="btn primary" onClick={() => setForm('new')}>
            + 新增角色
          </button>
        ) : null}
      </div>

      {!user ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>
            你还未登录。<Link to="/login">去登录</Link>
          </p>
        </div>
      ) : !user.playerId ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>
            这个账号还没有关联群友，所以看不到车次也不能维护角色。请联系管理员在「系统设置」里给你绑定群友（账号是按 QQ 自动绑定的，一般不会出现这种情况）。
          </p>
        </div>
      ) : (
        <>
          <div className="card" style={{ marginBottom: 20 }}>
            <div className="card-title">
              显示名称
              <span className="muted">群里、车次上显示的就是这个名字，改完立即生效</span>
            </div>
            <form onSubmit={saveDisplayName} style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <label className="field" style={{ marginBottom: 0, minWidth: 260, maxWidth: 360 }}>
                <span>我的显示名称</span>
                <input
                  type="text"
                  value={displayName}
                  maxLength={64}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="例如 群友N"
                />
              </label>
              <button className="btn primary" type="submit" disabled={savingName || !displayName.trim()}>
                {savingName ? '保存中…' : '保存名称'}
              </button>
              {user.playerNickname && user.playerNickname !== displayName ? (
                <span className="muted" style={{ fontSize: 12.5 }}>
                  当前是「{user.playerNickname}」
                </span>
              ) : null}
            </form>
          </div>

          <div className="card-title" style={{ marginBottom: 12 }}>
            我的游戏角色
            <span className="muted">职业 / 战力 / 备注（备注会在车次上悬停角色时显示）</span>
          </div>
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    <th style={{ width: 70 }}>#</th>
                    <th style={{ width: 200 }}>职业</th>
                    <th style={{ width: 150 }}>战力（M / Million）</th>
                    <th style={{ width: 110 }}>主号</th>
                    <th title="填了备注后，在车次卡片 / 车次一览表里把鼠标停在你的角色上就会显示">备注（悬停角色时显示）</th>
                    <th style={{ width: 150 }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {characters.map((character, index) => (
                    <tr key={character.id}>
                      <td>{index + 1}</td>
                      <td>
                        <strong>{character.job}</strong>
                      </td>
                      <td>{character.power}M</td>
                      <td>{character.mainChar ? '★ 主号' : <span className="muted">—</span>}</td>
                      <td>{character.note ? character.note : <span className="muted">—</span>}</td>
                      <td>
                        <div style={{ display: 'flex', gap: 6 }}>
                          <button className="btn small" onClick={() => setForm(character)}>
                            编辑
                          </button>
                          <button className="btn small danger" onClick={() => remove(character)}>
                            删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {characters.length === 0 && !loading ? (
              <p className="muted" style={{ padding: 16 }}>
                还没有登记角色，点右上角「+ 新增角色」把自己在玩的职业和战力加上吧。
              </p>
            ) : null}
          </div>
        </>
      )}

      {form ? (
        <CharacterForm
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null);
            load();
          }}
        />
      ) : null}
    </div>
  );
}

function CharacterForm({
  initial,
  onClose,
  onSaved,
}: {
  initial: CharacterView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { notify } = useToast();
  const [job, setJob] = useState(initial?.job ?? '');
  const [power, setPower] = useState(initial ? String(initial.power) : '');
  const [mainChar, setMainChar] = useState(initial?.mainChar ?? false);
  const [note, setNote] = useState(initial?.note ?? '');
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const body = { job: job.trim(), power: Number(power) || 0, mainChar, note };
      if (initial) {
        await api.updateMyCharacter(initial.id, body);
      } else {
        await api.createMyCharacter(body);
      }
      notify('已保存');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={initial ? `编辑角色：${initial.job}` : '新增角色'}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="my-character-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="my-character-form" onSubmit={submit}>
        <label className="field">
          <span>职业</span>
          <input
            type="text"
            value={job}
            onChange={(e) => setJob(e.target.value)}
            list="my-job-presets"
            placeholder="例如 Kanna / 剑豪 / 光枪"
            required
          />
          <datalist id="my-job-presets">
            {JOB_PRESETS.map((item) => (
              <option key={item} value={item} />
            ))}
          </datalist>
        </label>
        <label className="field">
          <span>战力（单位：M / Million，例如 185M）</span>
          <input
            type="number"
            min={0}
            value={power}
            onChange={(e) => setPower(e.target.value)}
            placeholder="例如 185（=185M）"
          />
        </label>
        <label className="field">
          <span>备注（可选）</span>
          <input type="text" value={note ?? ''} onChange={(e) => setNote(e.target.value)} />
        </label>
        <label className="field" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            checked={mainChar}
            onChange={(e) => setMainChar(e.target.checked)}
            style={{ width: 'auto' }}
          />
          <span style={{ margin: 0 }}>这是我的主号</span>
        </label>
      </form>
    </Modal>
  );
}
