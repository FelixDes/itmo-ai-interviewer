import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { InterviewDetail, Report } from "../api/types";
import ReportView from "../components/ReportView";
import { ErrorBox, formatDate, Spinner } from "../components/ui";

export default function ReportPage() {
  const { id } = useParams<{ id: string }>();
  const [interview, setInterview] = useState<InterviewDetail | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    const iv = await api.interview(id!);
    setInterview(iv);
    setReport(iv.reportAvailable && iv.status === "READY_REPORT" ? await api.report(id!) : null);
    return iv;
  };

  useEffect(() => { load().catch((e) => setError(e.message)); }, [id]);

  // Пока карточка собирается, ждём: это может занять до полутора минут
  useEffect(() => {
    if (interview?.status !== "ANALYZING") return;
    const timer = setInterval(() => { load().catch(() => undefined); }, 3000);
    return () => clearInterval(timer);
  }, [interview?.status]);

  if (!interview) return <div className="layout"><span className="spinner" /></div>;

  const share = async () => {
    setBusy(true);
    try { await load(); await api.share(interview.id); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const revoke = async () => {
    setBusy(true);
    try { await api.revokeShare(interview.id); await load(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  };

  const reanalyze = async () => {
    setBusy(true);
    try { await api.reanalyze(interview.id); await load(); }
    catch (e) { setError(e instanceof ApiError ? e.message : (e as Error).message); }
    finally { setBusy(false); }
  };

  const actions = (
    <div className="row">
      <button onClick={reanalyze} disabled={busy}>Пересобрать карточку</button>
      {interview.share && !interview.share.revoked
        ? <button className="danger" onClick={revoke} disabled={busy}>Отозвать ссылку</button>
        : <button className="primary" onClick={share} disabled={busy}>Ссылка нанимающему</button>}
    </div>
  );

  return (
    <div className="layout">
      <p className="sub">
        <Link to={`/vacancies/${interview.vacancyId}/interviews`}>← к списку кандидатов</Link>
      </p>

      <ErrorBox error={error} />

      {interview.share && !interview.share.revoked && (
        <div className="panel">
          <h3 style={{ marginTop: 0 }}>Ссылка для нанимающего менеджера</h3>
          <p className="small muted">
            По ней виден только этот кандидат. Действует до {formatDate(interview.share.expiresAt)}.
          </p>
          <div className="row">
            <input className="mono grow" readOnly value={interview.share.url} onFocus={(e) => e.target.select()} />
            <button onClick={() => navigator.clipboard.writeText(interview.share!.url)}>Копировать</button>
          </div>
        </div>
      )}

      {report ? (
        <ReportView report={report} actions={actions} />
      ) : interview.status === "ANALYZING" ? (
        <div className="panel">
          <h1>{interview.candidateName}</h1>
          <Spinner text="Собираем карточку. Модель разбирает ответы, это занимает до полутора минут." />
        </div>
      ) : interview.status === "FAILED" ? (
        <div className="panel">
          <h1>{interview.candidateName}</h1>
          <p className="error">
            Не удалось собрать карточку: {interview.failure?.message ?? "неизвестная ошибка"}
          </p>
          <button className="primary" onClick={reanalyze} disabled={busy}>Повторить анализ</button>
        </div>
      ) : (
        <div className="panel">
          <h1>{interview.candidateName}</h1>
          <p className="muted">
            Интервью ещё не завершено: отвечено {interview.answered} из {interview.planned}.
          </p>
        </div>
      )}
    </div>
  );
}
