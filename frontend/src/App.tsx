import { useEffect, useState } from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { api, ApiError } from "./api/client";
import type { CurrentUser } from "./api/types";
import LoginPage from "./pages/LoginPage";
import VacanciesPage from "./pages/VacanciesPage";
import VacancyPage from "./pages/VacancyPage";
import InterviewsPage from "./pages/InterviewsPage";
import ReportPage from "./pages/ReportPage";
import CandidatePage from "./pages/CandidatePage";
import SharedReportPage from "./pages/SharedReportPage";

export default function App() {
  return (
    <Routes>
      {/* Кандидат и менеджер ходят по токену, без логина и без общей шапки */}
      <Route path="/s/:token" element={<CandidatePage />} />
      <Route path="/r/:token" element={<SharedReportPage />} />
      <Route path="/*" element={<RecruiterArea />} />
    </Routes>
  );
}

function RecruiterArea() {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [checked, setChecked] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    api.me()
      .then(setUser)
      .catch((e) => {
        if (!(e instanceof ApiError && e.status === 401)) console.error(e);
      })
      .finally(() => setChecked(true));
  }, []);

  if (!checked) return <div className="layout"><span className="spinner" /></div>;
  if (!user) return <LoginPage onLogin={setUser} />;

  const logout = async () => {
    await api.logout();
    setUser(null);
    navigate("/");
  };

  return (
    <>
      <header className="top">
        <Link to="/" className="brand" style={{ textDecoration: "none", color: "inherit" }}>
          AI-интервьюер
        </Link>
        <span className="spacer" />
        <span className="muted small">{user.displayName}</span>
        <button className="link" onClick={logout}>Выйти</button>
      </header>
      <Routes>
        <Route path="/" element={<VacanciesPage />} />
        <Route path="/vacancies/:id" element={<VacancyPage />} />
        <Route path="/vacancies/:id/interviews" element={<InterviewsPage />} />
        <Route path="/interviews/:id" element={<ReportPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
