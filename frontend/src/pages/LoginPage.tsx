import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';

export default function LoginPage() {
  const { login, user } = useAuth();
  const { notify } = useToast();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const result = await login(username.trim(), password);
      if (result.mustChangePassword) {
        notify('登录成功：请先设置你自己的密码', 'info');
        navigate('/');
        return;
      }
      notify('登录成功，去「我的车次」看看你要打哪几趟');
      navigate('/');
    } catch (error) {
      notify(error instanceof Error ? error.message : '登录失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submit}>
        <h1>🌟 GMSR BOSS 组队排班</h1>
        <p className="muted" style={{ marginTop: 0 }}>
          请输入你的账号与密码登录。
        </p>
        <label className="field">
          <span>用户名</span>
          <input
            type="text"
            value={username}
            autoComplete="username"
            onChange={(e) => setUsername(e.target.value)}
            placeholder="请输入用户名"
            required
          />
        </label>
        <label className="field">
          <span>密码</span>
          <input
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
            placeholder="请输入密码"
            required
          />
        </label>
        <div className="modal-actions" style={{ marginTop: 8, justifyContent: 'space-between' }}>
          <Link className="btn ghost" to="/">
            先看看排班
          </Link>
          <button className="btn primary" type="submit" disabled={busy}>
            {busy ? '登录中…' : '登录'}
          </button>
        </div>
        {user ? <p className="muted">当前已登录：{user.displayName}</p> : null}
      </form>
    </div>
  );
}
