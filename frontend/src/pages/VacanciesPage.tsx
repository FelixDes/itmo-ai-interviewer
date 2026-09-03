import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import type { VacancyListItem } from "../api/types";
import { Badge, ErrorBox, formatDate } from "../components/ui";

export default function VacanciesPage() {
  const [items, setItems] = useState<VacancyListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  const load = () => api.vacancies().then(setItems).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  const seed = async () => {
    setBusy(true);
    try {
      const result = await api.seedDemo();
      navigate(`/vacancies/${result.vacancyId}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    setBusy(true);
    try {
      const vacancy = await api.createVacancy({
        title: "Новая вакансия",
        grade: "MIDDLE",
        description: "",
        requirements: [
          { text: "Опишите первое требование", kind: "MUST", weight: 3, stopFactor: false, notVerifiable: false },
        ],
      });
      navigate(`/vacancies/${vacancy.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="layout">
      <div className="row between">
        <div>
          <h1>Вакансии</h1>
          <p className="sub">Настройте требования и вопросы, затем приглашайте кандидатов</p>
        </div>
        <div className="row">
          <button onClick={seed} disabled={busy}>Демо-вакансия</button>
          <button className="primary" onClick={create} disabled={busy}>Создать вакансию</button>
        </div>
      </div>

      <ErrorBox error={error} />

      {!items ? <span className="spinner" /> : items.length === 0 ? (
        <div className="panel muted">
          Вакансий пока нет. Нажмите «Демо-вакансия», чтобы получить готовый пример
          Middle+ Python с эталонными вопросами.
        </div>
      ) : (
        <div className="panel" style={{ padding: 0 }}>
          <table>
            <thead>
              <tr>
                <th>Название</th>
                <th>Грейд</th>
                <th>Вопросы</th>
                <th>Кандидатов</th>
                <th>Создана</th>
              </tr>
            </thead>
            <tbody>
              {items.map((v) => (
                <tr key={v.id} className="clickable" onClick={() => navigate(`/vacancies/${v.id}`)}>
                  <td>{v.title}</td>
                  <td className="muted">{v.grade}</td>
                  <td>
                    {v.hasFrozenQuestionSet
                      ? <Badge tone="ok">готовы</Badge>
                      : <Badge tone="warn">не зафиксированы</Badge>}
                  </td>
                  <td>{v.interviewCount}</td>
                  <td className="muted small">{formatDate(v.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
