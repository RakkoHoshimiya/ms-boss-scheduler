import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import type { CharacterView, PlayerView } from '../api/types';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';
import Modal from '../components/Modal';
import { JOB_PRESETS } from '../components/ui';

/** 群友角色一览表：QQ号 / 显示名称 / 各角色职业与战力，按战力从高到低排序。 */
export default function PlayersPage() {
  const { canEdit } = useAuth();
  const { notify } = useToast();
  const [players, setPlayers] = useState<PlayerView[]>([]);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [playerForm, setPlayerForm] = useState<PlayerView | 'new' | null>(null);
  const [characterForm, setCharacterForm] = useState<{ player: PlayerView; character?: CharacterView } | null>(null);

  const load = () => {
    setLoading(true);
    api
      .players()
      .then(setPlayers)
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const filtered = useMemo(() => {
    const text = keyword.trim().toLowerCase();
    if (!text) {
      return players;
    }
    return players.filter(
      (player) =>
        player.nickname.toLowerCase().includes(text) ||
        (player.qq ?? '').includes(text) ||
        player.characters.some((c) => c.job.toLowerCase().includes(text)),
    );
  }, [players, keyword]);

  const removePlayer = async (player: PlayerView) => {
    if (!window.confirm(`确认删除「${player.nickname}」及其全部角色？相关车次席位也会被移除。`)) {
      return;
    }
    try {
      await api.deletePlayer(player.id);
      notify('已删除');
      load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  const removeCharacter = async (character: CharacterView) => {
    if (!window.confirm(`确认删除角色「${character.playerNickname} · ${character.job}」？`)) {
      return;
    }
    try {
      await api.deleteCharacter(character.id);
      notify('已删除角色');
      load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  const noQq = players.filter((player) => !player.qq).length;

  return (
    <div>
      <div className="page-head">
        <h1>群友角色一览表</h1>
        <span className="sub">
          按战力从高到低排序 · 共 {players.length} 位群友 / {players.reduce((sum, p) => sum + p.characterCount, 0)} 个角色
          {noQq > 0 ? ` · ${noQq} 位还没登记 QQ 号` : ''}
        </span>
        <div className="spacer" />
        {canEdit ? (
          <button className="btn primary" onClick={() => setPlayerForm('new')}>
            + 新增群友
          </button>
        ) : null}
      </div>

      <div className="toolbar">
        <input
          type="text"
          placeholder="搜索 QQ号 / 显示名称 / 职业…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ maxWidth: 300 }}
        />
        <button className="btn" onClick={load}>
          刷新
        </button>
        {loading ? <span className="muted">加载中…</span> : null}
      </div>

      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th style={{ width: 130 }}>QQ号</th>
                <th style={{ width: 170 }}>显示名称</th>
                <th style={{ width: 90 }}>职业数</th>
                <th>角色（职业 / 战力，单位 M）</th>
                {canEdit ? <th style={{ width: 150 }}>操作</th> : null}
              </tr>
            </thead>
            <tbody>
              {filtered.map((player) => (
                <tr key={player.id}>
                  <td>
                    {player.qq ? (
                      <code className="qq">{canEdit ? player.qq : maskQq(player.qq)}</code>
                    ) : (
                      <span className="muted">
                        未登记
                      </span>
                    )}
                  </td>
                  <td>
                    <strong>{player.nickname}</strong>
                    {!player.active ? <span className="tag"> 暂停</span> : null}
                    {player.remark ? <div className="muted">{player.remark}</div> : null}
                  </td>
                  <td>{player.characterCount}</td>
                  <td>
                    <div className="char-grid">
                      {player.characters.map((character) => (
                        <button
                          type="button"
                          key={character.id}
                          className="char-item"
                          onClick={() => canEdit && setCharacterForm({ player, character })}
                          disabled={!canEdit}
                          title={canEdit ? '点击编辑这个角色' : undefined}
                        >
                          <span className="job">
                            {character.mainChar ? '★ ' : ''}
                            {character.job}
                          </span>
                          <span className="power">{character.power}M</span>
                        </button>
                      ))}
                      {player.characters.length === 0 ? (
                        <span className="muted" style={{ fontSize: 12.5 }}>
                          还没登记角色
                        </span>
                      ) : null}
                      {canEdit ? (
                        <button type="button" className="char-item add" onClick={() => setCharacterForm({ player })}>
                          + 添加角色
                        </button>
                      ) : null}
                    </div>
                  </td>
                  {canEdit ? (
                    <td>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        <button className="btn small" onClick={() => setPlayerForm(player)}>
                          编辑
                        </button>
                        <button className="btn small danger" onClick={() => removePlayer(player)}>
                          删除
                        </button>
                      </div>
                    </td>
                  ) : null}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {filtered.length === 0 ? (
          <p className="muted" style={{ padding: 16 }}>
            没有匹配的群友
          </p>
        ) : null}
      </div>

      {playerForm ? (
        <PlayerFormModal
          initial={playerForm === 'new' ? null : playerForm}
          onClose={() => setPlayerForm(null)}
          onSaved={() => {
            setPlayerForm(null);
            load();
          }}
        />
      ) : null}

      {characterForm ? (
        <CharacterFormModal
          player={characterForm.player}
          initial={characterForm.character ?? null}
          onClose={() => setCharacterForm(null)}
          onSaved={() => {
            setCharacterForm(null);
            load();
          }}
          onDelete={removeCharacter}
        />
      ) : null}
    </div>
  );
}

function PlayerFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: PlayerView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { notify } = useToast();
  const [nickname, setNickname] = useState(initial?.nickname ?? '');
  const [qq, setQq] = useState(initial?.qq ?? '');
  const [remark, setRemark] = useState(initial?.remark ?? '');
  const [active, setActive] = useState(initial?.active ?? true);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const body = { nickname: nickname.trim(), qq: qq.trim() || null, remark };
      if (initial) {
        await api.updatePlayer(initial.id, { ...body, active });
      } else {
        await api.createPlayer(body);
      }
      notify(initial ? '已保存' : '已新增群友');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={initial ? `编辑群友：${initial.nickname}` : '新增群友'}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="player-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="player-form" onSubmit={submit}>
        <label className="field">
          <span>显示名称（群里怎么叫）</span>
          <input type="text" value={nickname} onChange={(e) => setNickname(e.target.value)} required />
        </label>
        <label className="field">
          <span>QQ 号（5-12 位数字）</span>
          <input
            type="text"
            inputMode="numeric"
            value={qq ?? ''}
            onChange={(e) => setQq(e.target.value)}
            placeholder="例如 100000003"
          />
        </label>
        <label className="field">
          <span>备注（可选）</span>
          <input type="text" value={remark ?? ''} onChange={(e) => setRemark(e.target.value)} />
        </label>
        {initial ? (
          <label className="field">
            <span>状态</span>
            <select value={active ? '1' : '0'} onChange={(e) => setActive(e.target.value === '1')}>
              <option value="1">正常参与</option>
              <option value="0">暂停参与</option>
            </select>
          </label>
        ) : null}
      </form>
    </Modal>
  );
}

function CharacterFormModal({
  player,
  initial,
  onClose,
  onSaved,
  onDelete,
}: {
  player: PlayerView;
  initial: CharacterView | null;
  onClose: () => void;
  onSaved: () => void;
  onDelete: (character: CharacterView) => void;
}) {
  const { notify } = useToast();
  const [job, setJob] = useState(initial?.job ?? '');
  const [power, setPower] = useState<string>(initial ? String(initial.power) : '');
  const [mainChar, setMainChar] = useState(initial?.mainChar ?? false);
  const [note, setNote] = useState(initial?.note ?? '');
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const body = {
        playerId: player.id,
        job: job.trim(),
        power: Number(power) || 0,
        mainChar,
        note,
      };
      if (initial) {
        await api.updateCharacter(initial.id, body);
      } else {
        await api.createCharacter(body);
      }
      notify('已保存角色');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={`${initial ? '编辑' : '新增'}角色 · ${player.nickname}`}
      onClose={onClose}
      footer={
        <>
          {initial ? (
            <button
              className="btn danger"
              type="button"
              onClick={() => {
                onDelete(initial);
                onClose();
              }}
            >
              删除角色
            </button>
          ) : null}
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="character-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="character-form" onSubmit={submit}>
        <label className="field">
          <span>职业名称</span>
          <input
            type="text"
            value={job}
            onChange={(e) => setJob(e.target.value)}
            list="job-presets"
            placeholder="例如 Kanna / 剑豪 / 光枪"
            required
          />
          <datalist id="job-presets">
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
          <span style={{ margin: 0 }}>标记为主号</span>
        </label>
      </form>
    </Modal>
  );
}

/** 非管理员只显示脱敏后的 QQ 号，避免在公开页面暴露完整账号。 */
function maskQq(qq: string): string {
  if (qq.length <= 6) {
    return qq.slice(0, 2) + '****';
  }
  return `${qq.slice(0, 3)}****${qq.slice(-3)}`;
}
