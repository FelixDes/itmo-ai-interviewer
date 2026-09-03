import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { InterviewDetail, InterviewListItem, Vacancy } from "../api/types";
import { ErrorBox, formatDate, RecommendationBadge, Spinner, StatusBadge } from "../components/ui";

export default function InterviewsPage() {
  const { id } = useParams<{ id: string }>();
  const [vacancy, setVacancy] = useState<Vacancy | null>(null);
  const [items, setItems] = useState<InterviewListItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<InterviewDetail | null>(null);
  const navigate = useNavigate();

  const load = async () => {
    setVacancy(await api.vacancy(id!));
    setItems(await api.interviews(id!));
  };

  useEffect(() => {
    load().catch((e) => setError(e.message));
    // Пока есть незавершённые интервью, подтягиваем статусы
    const timer = setInterval(() => { api.interviews(id!).then(setItems).catch(() => undefined); }, 5000);
    return () => clearInterval(timer);
  }, [id]);

  if (!vacancy) return <div className="layout"><span className="spinner" /></div>;

  return (
    <div className="layout">
      <div className="row between">
        <div>
          <h1>Кандидаты</h1>
          <p className="sub">
            <Link to={`/vacancies/${vacancy.id}`}>{vacancy.title}</Link>
          </p>
        </div>
      </div>

      <ErrorBox error={error} />

      <NewInterviewForm vacancy={vacancy} onCreated={(iv) => { setCreated(iv); load(); }} />

      {created && (
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Ссылка для {created.candidateName}</h3>
          <p className="small muted">
            Отправьте её кандидату сами — почтой или в мессенджере. Действует до {formatDate(created.expiresAt)}.
          </p>
          <div className="row">
            <input className="mono grow" readOnly value={created.candidateUrl} onFocus={(e) => e.target.select()} />
            <button onClick={() => navigator.clipboard.writeText(created.candidateUrl)}>Копировать</button>
            <button className="link" onClick={() => setCreated(null)}>Скрыть</button>
          </div>
        </div>
      )}

      <div className="panel" style={{ padding: 0 }}>
        {items.length === 0 ? (
          <div style={{ padding: 16 }} className="muted">Кандидатов пока нет.</div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Кандидат</th>
                <th>Статус</th>
                <th>Прогресс</th>
                <th>Итог</th>
                <th>Создано</th>
              </tr>
            </thead>
            <tbody>
              {items.map((iv) => (
                <tr key={iv.id}
                    className={iv.status === "READY_REPORT" ? "clickable" : ""}
                    onClick={() => iv.status === "READY_REPORT" && navigate(`/interviews/${iv.id}`)}>
                  <td>{iv.candidateName}</td>
                  <td><StatusBadge status={iv.status} /></td>
                  <td className="muted small">{iv.answered} / {iv.planned}</td>
                  <td>
                    {iv.recommendation
                      ? <span className="row"><RecommendationBadge value={iv.recommendation} /> <span className="muted small">{iv.overallScore}</span></span>
                      : <span className="muted small">—</span>}
                  </td>
                  <td className="muted small">{formatDate(iv.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function NewInterviewForm({ vacancy, onCreated }: {
  vacancy: Vacancy;
  onCreated: (iv: InterviewDetail) => void;
}) {
  const [name, setName] = useState("");
  const [resume, setResume] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ready = vacancy.activeQuestionSet !== null;

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      onCreated(await api.createInterview(vacancy.id, name.trim(), resume.trim() || null));
      setName("");
      setResume("");
    } catch (e) {
      setError(e instanceof ApiError && e.code === "QUESTION_SET_NOT_FROZEN"
        ? "Сначала зафиксируйте набор вопросов на странице вакансии"
        : (e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Новое интервью</h2>
      {!ready && (
        <p className="error small">
          У вакансии нет зафиксированного набора вопросов —{" "}
          <Link to={`/vacancies/${vacancy.id}`}>зафиксируйте его</Link>.
        </p>
      )}
      <div className="field">
        <label>Имя кандидата</label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Иван Иванов" />
      </div>
      <div className="field">
        <label>Резюме текстом (необязательно)</label>
        <textarea value={resume} onChange={(e) => setResume(e.target.value)}
          placeholder="Вставьте текст резюме — по нему добавятся персональные вопросы" />
      </div>
      {error && <p className="error small">{error}</p>}
      <button className="primary" disabled={busy || !ready || name.trim().length === 0} onClick={submit}>
        {busy ? <Spinner text="готовим вопросы, до минуты" /> : "Создать и получить ссылку"}
      </button>
    </div>
  );
}
