import { useEffect, useRef } from "react";
import { apiClient } from "@/lib/apiClient";

type SessionType = "single" | "routine";

type UseSessionLoggingOptions = {
  sport_pk: string;
  ex_type: string;
  exerciseName: string | undefined;
  sessionType: SessionType;
};

const SESSION_TYPE_MAP = {
  single: "exercise_id",
  routine: "playlist_id",
};

export function useSessionLogging({
  sport_pk,
  ex_type,
  exerciseName,
  sessionType,
}: UseSessionLoggingOptions) {
  const sessionIdRef = useRef<string | null>(null);
  const lastLoggedInfo = useRef<string | null>(null);

  useEffect(() => {
    // 현재 세션을 구분하기 위한 키 생성
    const currentInfo = `${sessionType}-${ex_type}-${sport_pk}`;

    if (lastLoggedInfo.current !== currentInfo && exerciseName) {
      lastLoggedInfo.current = currentInfo;

      const fetchLogStart = async () => {
        try {
          const data = await apiClient.post<{ session_id: string }>(
            "/log/session/start/",
            {
              [SESSION_TYPE_MAP[sessionType]]: sport_pk,
              exercise_name: exerciseName,
            }
          );
          sessionIdRef.current = data.session_id;
        } catch (error) {
          console.error("로깅 API 호출 중 오류 발생:", error);
        }
      };

      fetchLogStart();
    }

    return () => {
      const fetchLogEnd = async () => {
        try {
          if (sessionIdRef.current) {
            await apiClient.post(`/log/session/${sessionIdRef.current}/end/`);
            sessionIdRef.current = null;
          }
        } catch (error) {
          console.error("로깅 API 호출 중 오류 발생:", error);
        }
      };

      fetchLogEnd();
    };
  }, [exerciseName, ex_type, sport_pk, sessionType]);
}
