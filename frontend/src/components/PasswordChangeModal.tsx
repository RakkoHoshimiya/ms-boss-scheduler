import { useState } from 'react';
import { api } from '../api/client';
import Modal from './Modal';
import { useToast } from './Toast';

/** 修改密码弹窗；required=true 时用于「首次登录必须改密码」。 */
export default function PasswordChangeModal({
  required = false,
  onClose,
  onDone,
}: {
  required?: boolean;
  onClose: () => void;
  onDone?: () => void;
}) {
  const { notify } = useToast();
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (newPassword !== confirm) {
      notify('两次输入的新密码不一致', 'error');
      return;
    }
    if (newPassword.length < 6) {
      notify('新密码至少 6 位', 'error');
      return;
    }
    setBusy(true);
    try {
      await api.changePassword(oldPassword, newPassword);
      notify('密码已修改');
      onDone?.();
      onClose();
    } catch (error) {
      notify(error instanceof Error ? error.message : '修改失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={required ? '首次登录：请先修改密码' : '修改我的密码'}
      onClose={required ? () => undefined : onClose}
      footer={
        <>
          {required ? null : (
            <button className="btn" type="button" onClick={onClose}>
              取消
            </button>
          )}
          <button className="btn primary" type="submit" form="password-form" disabled={busy}>
            {busy ? '保存中…' : '保存新密码'}
          </button>
        </>
      }
    >
      {required ? (
        <p className="muted" style={{ marginTop: 0 }}>
          为了账号安全，请先设置一个只有你自己知道的新密码。
        </p>
      ) : null}
      <form id="password-form" onSubmit={submit}>
        <label className="field">
          <span>当前密码</span>
          <input
            type="password"
            value={oldPassword}
            onChange={(e) => setOldPassword(e.target.value)}
            autoComplete="current-password"
            required
          />
        </label>
        <label className="field">
          <span>新密码（至少 6 位）</span>
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            autoComplete="new-password"
            required
            minLength={6}
          />
        </label>
        <label className="field">
          <span>再输一次新密码</span>
          <input
            type="password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
            required
            minLength={6}
          />
        </label>
      </form>
    </Modal>
  );
}
