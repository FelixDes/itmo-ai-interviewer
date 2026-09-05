import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { EvaluationMode, Grade, QuestionSet, RequirementInput, Vacancy } from "../api/types";
import { Badge, ErrorBox, Spinner } from "../components/ui";

const GRADES: Grade[] = ["JUNIOR", "MIDDLE", "MIDDLE_PLUS", "SENIOR", "LEAD"];

export default function VacancyPage() {
  const { id } = useParams<{ id: string }>();
  const [vacancy, setVacancy] = useState<Vacancy | null>(null);
  const [draft, setDraft] = useState<{
    title: string;
    grade: Grade;
    description: string;
    requirements: RequirementInput[];
    evaluationMode: EvaluationMode;
  } | null>(null);
  const [set, setSet] = useState<QuestionSet | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const loadSet = async (v: Vacancy) => {
    // Показываем черновик, если он есть: с ним рекрутер сейчас и работает
    const ref = v.draftQuestionSet ?? v.activeQuestionSet;
    setSet(ref ? await api.questionSet(ref.id) : null);
  };

  const load = async () => {
    const v = await api.vacancy(id!);
    setVacancy(v);
    setDraft({
      title: v.title, grade: v.grade, description: v.description,
      requirements: v.requirements.map((r) => ({ ...r })),
      evaluationMode: v.evaluationMode,
    });
    await loadSet(v);
  };

  useEffect(() => { load().catch((e) => setError(e.message)); }, [id]);

  const run = async (key: string, action: () => Promise<void>) => {
    setBusy(key);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof ApiError ? `${e.message} (${e.code})` : (e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  if (!vacancy || !draft) return <div className="layout"><span className="spinner" /></div>;

  const patchRequirement = (index: number, patch: Partial<RequirementInput>) =>
    setDraft({
      ...draft,
      requirements: draft.requirements.map((r, i) => (i === index ? { ...r, ...patch } : r)),
    });

  return (
    <div className="layout">
      <div className="row between">
        <div>
          <h1>{vacancy.title}</h1>
          <p className="sub">
            Версия вопросов:{" "}
            {vacancy.activeQuestionSet
              ? `${vacancy.activeQuestionSet.version} (зафиксирована)`
              : "нет зафиксированной"}
          </p>
        </div>
        <Link to={`/vacancies/${vacancy.id}/interviews`}>
          <button className="primary">Кандидаты</button>
        </Link>
      </div>

      <ErrorBox error={error} />

      <div className="panel">
        <h2 style={{ marginTop: 0 }}>Описание</h2>
        <div className="row">
          <div className="field grow">
            <label>Название</label>
            <input value={draft.title} onChange={(e) => setDraft({ ...draft, title: e.target.value })} />
          </div>
          <div className="field" style={{ width: 170 }}>
            <label>Грейд</label>
            <select value={draft.grade} onChange={(e) => setDraft({ ...draft, grade: e.target.value as Grade })}>
              {GRADES.map((g) => <option key={g} value={g}>{g}</option>)}
            </select>
          </div>
        </div>
        <div className="field">
          <label>Задачи роли</label>
          <textarea value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
        </div>

        <h2>Как считать итог</h2>
        <div className="row" style={{ marginBottom: 8 }}>
          <button
            className={draft.evaluationMode === "RULES" ? "primary" : ""}
            onClick={() => setDraft({ ...draft, evaluationMode: "RULES" })}
          >
            По правилам
          </button>
          <button
            className={draft.evaluationMode === "LLM" ? "primary" : ""}
            onClick={() => setDraft({ ...draft, evaluationMode: "LLM" })}
          >
            Полностью моделью
          </button>
        </div>
        <p className="small muted" style={{ marginTop: 0 }}>
          {draft.evaluationMode === "RULES" ? (
            <>
              Вердикты и балл считаются из оценок ответов по весам требований,
              модель пишет только формулировки. Результат воспроизводим: одни и те же
              ответы всегда дают один и тот же балл, и его можно пересчитать вручную.
            </>
          ) : (
            <>
              Модель выносит вердикты, ставит балл и рекомендацию сама. Настраивать
              веса и пороги не нужно, но результат перестаёт быть воспроизводимым:
              один и тот же набор ответов может дать разный балл, и пересчитать его
              не по чему. Веса и стоп-факторы ниже становятся подсказкой для модели,
              а не формулой.
            </>
          )}
        </p>

        <h2>Требования</h2>
        <p className="small muted">
          Вес влияет на итоговый балл. «Не проверяется» — навык попадёт в карточку
          как непроверенный, а не как отсутствующий.
        </p>
        <table>
          <thead>
            <tr>
              <th>Требование</th>
              <th style={{ width: 110 }}>Тип</th>
              <th style={{ width: 70 }}>Вес</th>
              <th style={{ width: 90 }}>Стоп-фактор</th>
              <th style={{ width: 110 }}>Не проверяется</th>
              <th style={{ width: 40 }} />
            </tr>
          </thead>
          <tbody>
            {draft.requirements.map((r, i) => (
              <tr key={i}>
                <td><input value={r.text} onChange={(e) => patchRequirement(i, { text: e.target.value })} /></td>
                <td>
                  <select value={r.kind} onChange={(e) => patchRequirement(i, { kind: e.target.value as "MUST" | "NICE" })}>
                    <option value="MUST">обязательное</option>
                    <option value="NICE">желательное</option>
                  </select>
                </td>
                <td>
                  <select value={r.weight} onChange={(e) => patchRequirement(i, { weight: Number(e.target.value) })}>
                    {[1, 2, 3].map((w) => <option key={w} value={w}>{w}</option>)}
                  </select>
                </td>
                <td style={{ textAlign: "center" }}>
                  <input type="checkbox" style={{ width: "auto" }} checked={r.stopFactor}
                    onChange={(e) => patchRequirement(i, { stopFactor: e.target.checked })} />
                </td>
                <td style={{ textAlign: "center" }}>
                  <input type="checkbox" style={{ width: "auto" }} checked={r.notVerifiable}
                    onChange={(e) => patchRequirement(i, { notVerifiable: e.target.checked })} />
                </td>
                <td>
                  <button className="link danger"
                    onClick={() => setDraft({ ...draft, requirements: draft.requirements.filter((_, j) => j !== i) })}>
                    ✕
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row" style={{ marginTop: 12 }}>
          <button onClick={() => setDraft({
            ...draft,
            requirements: [...draft.requirements, { text: "", kind: "MUST", weight: 2, stopFactor: false, notVerifiable: false }],
          })}>
            Добавить требование
          </button>
          <span className="grow" />
          <button className="primary" disabled={busy !== null}
            onClick={() => run("save", async () => {
              await api.updateVacancy(vacancy.id, draft);
              await load();
            })}>
            {busy === "save" ? "Сохраняем…" : "Сохранить"}
          </button>
        </div>
      </div>

      <QuestionsPanel
        vacancy={vacancy}
        set={set}
        busy={busy}
        run={run}
        reload={load}
        setSet={setSet}
      />
    </div>
  );
}

function QuestionsPanel({ vacancy, set, busy, run, reload, setSet }: {
  vacancy: Vacancy;
  set: QuestionSet | null;
  busy: string | null;
  run: (key: string, action: () => Promise<void>) => Promise<void>;
  reload: () => Promise<void>;
  setSet: (s: QuestionSet) => void;
}) {
  return (
    <div className="panel">
      <div className="row between">
        <h2 style={{ marginTop: 0 }}>Обязательное ядро вопросов</h2>
        <div className="row">
          <button disabled={busy !== null}
            onClick={() => run("ref", async () => {
              setSet(await api.generateQuestions(vacancy.id, "REFERENCE"));
              await reload();
            })}>
            Эталонные 6
          </button>
          <button className="primary" disabled={busy !== null}
            onClick={() => run("llm", async () => {
              setSet(await api.generateQuestions(vacancy.id, "LLM"));
              await reload();
            })}>
            {busy === "llm" ? <Spinner text="модель думает, до минуты" /> : "Сгенерировать моделью"}
          </button>
        </div>
      </div>

      <p className="small muted">
        Эти вопросы одинаковы для всех кандидатов вакансии — на этом держится
        их сопоставимость. Персональные вопросы по резюме добавляются автоматически.
      </p>

      {!set ? (
        <div className="muted">Вопросов пока нет. Сгенерируйте набор.</div>
      ) : (
        <>
          <div className="row" style={{ marginBottom: 12 }}>
            <Badge tone={set.frozen ? "ok" : "warn"}>
              версия {set.version}, {set.frozen ? "зафиксирована" : "черновик"}
            </Badge>
            <span className="muted small">источник: {set.source === "LLM" ? "модель" : "эталон"}</span>
          </div>

          {set.questions.map((q, i) => (
            <div key={q.id} className="field">
              <label>
                Вопрос {q.ord}
                {q.requirementId && (
                  <> · проверяет: {vacancy.requirements.find((r) => r.id === q.requirementId)?.text ?? "—"}</>
                )}
              </label>
              <textarea
                value={q.text}
                disabled={set.frozen}
                onChange={(e) => setSet({
                  ...set,
                  questions: set.questions.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)),
                })}
              />
              {q.strongSignals.length > 0 && (
                <div className="small muted">Сильный ответ: {q.strongSignals.join("; ")}</div>
              )}
            </div>
          ))}

          <div className="row">
            {set.frozen ? (
              <>
                <span className="muted small grow">
                  Набор зафиксирован. Правка создаст новую версию — уже прошедшие
                  кандидаты останутся на своей.
                </span>
                <button disabled={busy !== null}
                  onClick={() => run("revise", async () => {
                    setSet(await api.reviseQuestionSet(set.id));
                    await reload();
                  })}>
                  Создать новую версию
                </button>
              </>
            ) : (
              <>
                <button disabled={busy !== null}
                  onClick={() => run("saveQ", async () => {
                    setSet(await api.updateQuestions(set.id, set.questions.map((q) => ({
                      id: q.id, text: q.text, requirementId: q.requirementId, strongSignals: q.strongSignals,
                    }))));
                  })}>
                  Сохранить черновик
                </button>
                <span className="grow" />
                <button className="primary" disabled={busy !== null}
                  onClick={() => run("freeze", async () => {
                    await api.updateQuestions(set.id, set.questions.map((q) => ({
                      id: q.id, text: q.text, requirementId: q.requirementId, strongSignals: q.strongSignals,
                    })));
                    setSet(await api.freezeQuestionSet(set.id));
                    await reload();
                  })}>
                  Зафиксировать
                </button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
