import { useState, type FormEvent } from "react";
import { api } from "../api/client";
import type { CurrentUser } from "../api/types";
import { ErrorBox } from "../components/ui";

export default function LoginPage({ onLogin }: { onLogin: (user: CurrentUser) => void }) {
  const [username, setUsername] = useState("recruiter");
  const [password, setPassword] = useState("recruiter");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.login(username, password);
      onLogin(await api.me());
    } catch {
      setError("Неверный логин или пароль");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="layout narrow" style={{ maxWidth: 380, paddingTop: 80 }}>
      <h1>AI-интервьюер</h1>
      <p className="sub">Кабинет рекрутера</p>
      <form className="panel" onSubmit={submit}>
        <div className="field">
          <label>Логин</label>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label>Пароль</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="primary" disabled={busy} style={{ width: "100%" }}>
          {busy ? "Входим…" : "Войти"}
        </button>
      </form>
      <ErrorBox error={error} />
    </div>
  );
}
