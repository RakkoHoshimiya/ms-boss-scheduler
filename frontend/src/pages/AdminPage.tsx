import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { UserView } from '../api/types';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';
import Modal from '../components/Modal';
import PasswordChangeModal from '../components/PasswordChangeModal';

const ROLE_OPTIONS = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'VIEWER', label: '群友' },
];

export default function AdminPage() {
  const { user } = useAuth();
  const { notify } = useToast();
  const [users, setUsers] = useState<UserView[]>([]);
  const [form, setForm] = useState<UserView | 'new' | null>(null);
  const [passwordOpen, setPasswordOpen] = useState(false);

  const load = () => {
    api
      .users()
      .then(setUsers)
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'));
  };

  useEffect(load, []);

  const resetPassword = async (target: UserView) => {
    if (!window.confirm(`确认重置「${target.displayName}」的密码？\n重置后请通知本人尽快登录并设置自己的新密码。`)) {
      return;
    }
    try {
      await api.resetUserPassword(target.id);
      notify(`已重置「${target.displayName}」的密码，请通知本人尽快设置新密码`);
      load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '重置失败', 'error');
    }
  };

  const remove = async (target: UserView) => {
    if (!window.confirm(`确认删除账号「${target.username}」？`)) {
      return;
    }
    try {
      await api.deleteUser(target.id);
      notify('已删除账号');
      load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  return (
    <div>
      <div className="page-head">
        <h1>系统设置</h1>
        <span className="sub">账号与权限管理</span>
        <div className="spacer" />
        <button className="btn" onClick={() => setPasswordOpen(true)}>
          修改我的密码
        </button>
        <button className="btn primary" onClick={() => setForm('new')}>
          + 新增账号
        </button>
      </div>

      <div className="card">
        <div className="card-title">账号列表</div>
        <table>
          <thead>
            <tr>
              <th>用户名</th>
              <th>显示名</th>
              <th>账号管理</th>
              <th>BOSS 排班</th>
              <th style={{ width: 130 }}>密码状态</th>
              <th style={{ width: 250 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {users.map((item) => (
              <tr key={item.id}>
                <td>
                  {item.username}
                  {item.id === user?.id ? <span className="tag"> 当前登录</span> : null}
                </td>
                <td>{item.displayName}</td>
                <td>
                  {item.role === 'ADMIN' ? (
                    <span className="tag status-DONE">管理员</span>
                  ) : (
                    <span className="muted">群友</span>
                  )}
                </td>
                <td>
                  {item.role === 'ADMIN' || item.canSchedule ? (
                    <span className="tag status-DONE">可排班</span>
                  ) : (
                    <span className="muted">不可排班</span>
                  )}
                </td>
                <td>
                  {item.mustChangePassword ? (
                    <span className="tag status-PLANNED">待本人设置</span>
                  ) : (
                    <span className="muted">已自行修改</span>
                  )}
                </td>
                <td>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <button className="btn small" onClick={() => setForm(item)}>
                      编辑
                    </button>
                    <button className="btn small" onClick={() => resetPassword(item)}>
                      重置密码
                    </button>
                    <button className="btn small danger" onClick={() => remove(item)}>
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card">
        <div className="card-title">当前状态</div>
        <p className="muted" style={{ margin: 0 }}>
          登录账号：{user?.displayName}（{user?.username}） · 权限：
          {ROLE_OPTIONS.find((role) => role.value === user?.role)?.label}
        </p>
      </div>

      {form ? (
        <UserFormModal
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null);
            load();
          }}
        />
      ) : null}

      {passwordOpen ? <PasswordChangeModal onClose={() => setPasswordOpen(false)} /> : null}
    </div>
  );
}

function UserFormModal({
  initial,
  onClose,
  onSaved,
}: {
  initial: UserView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { notify } = useToast();
  const [username, setUsername] = useState(initial?.username ?? '');
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState(initial?.role ?? 'EDITOR');
  const [canSchedule, setCanSchedule] = useState(initial?.canSchedule ?? true);
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      if (initial) {
        await api.updateUser(initial.id, {
          username: initial.username,
          displayName: displayName.trim(),
          role,
          canSchedule,
          password: password || undefined,
        });
      } else {
        await api.createUser({
          username: username.trim(),
          password,
          displayName: displayName.trim(),
          role,
          canSchedule,
        });
      }
      notify('已保存账号');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={initial ? `编辑账号：${initial.username}` : '新增账号'}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="user-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="user-form" onSubmit={submit}>
        {!initial ? (
          <label className="field">
            <span>用户名</span>
            <input type="text" value={username} onChange={(e) => setUsername(e.target.value)} required />
          </label>
        ) : null}
        <label className="field">
          <span>显示名</span>
          <input type="text" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
        </label>
        <label className="field">
          <span>{initial ? '设置新密码（留空不修改）' : '密码（至少 6 位）'}</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required={!initial}
            minLength={6}
          />
        </label>
        <label className="field" style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 0 }}>
          <input
            type="checkbox"
            checked={role === 'ADMIN'}
            disabled={initial?.username === 'admin'}
            onChange={(e) => setRole(e.target.checked ? 'ADMIN' : 'VIEWER')}
            style={{ width: 'auto' }}
          />
          <span style={{ margin: 0 }}>
            管理员（可管理账号：新增 / 删除账号、分配权限、重置密码）
            {initial?.username === 'admin' ? ' — 内置管理员不可取消' : ''}
          </span>
        </label>
        <label className="field" style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 0 }}>
          <input
            type="checkbox"
            checked={role === 'ADMIN' || canSchedule}
            disabled={role === 'ADMIN'}
            onChange={(e) => setCanSchedule(e.target.checked)}
            style={{ width: 'auto' }}
          />
          <span style={{ margin: 0 }}>
            boss排班 权限（可以分配 BOSS 车次与阵容）
            {role === 'ADMIN' ? ' — 管理员默认拥有' : ''}
          </span>
        </label>
      </form>
    </Modal>
  );
}

