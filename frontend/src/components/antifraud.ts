import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { AntifraudEventType } from "../api/types";

/**
 * Простейший антифрод на стороне браузера.
 *
 * Ловит две вещи: уход со вкладки и подключённый второй экран — на нём удобно
 * держать подсказки. Обойти это несложно, и цель не в том, чтобы поймать всех:
 * событие остаётся сигналом для человека, а не доказательством нарушения
 * (Рамка §11). Кандидат предупреждён о фиксации на первом экране.
 */

/** null означает «браузер не умеет» — такую проверку молча пропускаем. */
export function detectSecondScreen(): boolean | null {
  if (typeof screen === "undefined" || !("isExtended" in screen)) return null;
  return (screen as Screen & { isExtended: boolean }).isExtended === true;
}

export const screenCheckSupported = () => detectSecondScreen() !== null;

export interface AntifraudState {
  /** Сколько раз кандидат уходил со вкладки */
  tabSwitches: number;
  /** Предупреждение, которое нужно показать и закрыть кнопкой */
  warning: string | null;
  dismissWarning: () => void;
  /** true — сейчас подключён второй экран, отвечать нельзя */
  secondScreen: boolean;
}

export function useAntifraud(token: string, enabled: boolean, active: boolean): AntifraudState {
  const [tabSwitches, setTabSwitches] = useState(0);
  const [warning, setWarning] = useState<string | null>(null);
  const [secondScreen, setSecondScreen] = useState(false);
  // Второй экран может быть подключён всё интервью — событие шлём на переход,
  // а не на каждую проверку, иначе карточка утонет в дублях
  const reportedScreen = useRef(false);

  const send = useCallback(
    (type: AntifraudEventType) => { if (enabled) void api.antifraudEvent(token, type); },
    [enabled, token],
  );

  useEffect(() => {
    if (!enabled || !active) return;

    const onVisibility = () => {
      if (!document.hidden) return;
      send("TAB_HIDDEN");
      setTabSwitches((count) => count + 1);
      setWarning(
        "Вы переключились на другое окно. Это зафиксировано и будет видно " +
        "рекрутеру. Отвечайте, не покидая вкладку с интервью.",
      );
    };
    const onCopy = () => send("COPY");
    const onPaste = () => send("PASTE");

    document.addEventListener("visibilitychange", onVisibility);
    document.addEventListener("copy", onCopy);
    document.addEventListener("paste", onPaste);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      document.removeEventListener("copy", onCopy);
      document.removeEventListener("paste", onPaste);
    };
  }, [enabled, active, send]);

  useEffect(() => {
    if (!enabled || !active) return;
    if (detectSecondScreen() === null) return; // браузер не поддерживает — пропускаем

    const check = () => {
      const extended = detectSecondScreen() === true;
      setSecondScreen(extended);
      if (extended && !reportedScreen.current) {
        reportedScreen.current = true;
        send("MULTIPLE_SCREENS");
        setWarning(
          "Обнаружен второй экран. Отключите дополнительный монитор, " +
          "иначе продолжить интервью не получится. Подключение зафиксировано.",
        );
      }
      if (!extended) reportedScreen.current = false;
    };

    check();
    // Событие change есть не везде, поэтому дублируем опросом
    const target = screen as unknown as EventTarget;
    target.addEventListener?.("change", check);
    const timer = setInterval(check, 3000);
    return () => {
      target.removeEventListener?.("change", check);
      clearInterval(timer);
    };
  }, [enabled, active, send]);

  return {
    tabSwitches,
    warning,
    dismissWarning: () => setWarning(null),
    secondScreen,
  };
}
