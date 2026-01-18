"use client";

import { useState, useEffect, useCallback } from "react";
import { useWakeWord } from "@/hooks/useWakeWord";
import Button from "@/components/common/Button";

/**
 * 운동 재생 페이지 - Wake Word 음성 제어 지원
 * "시선 코치" → 명령 모드 → "멈춤", "다음", "빠르게" 등 명령 실행
 */
export default function ExercisePlayerPage() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentExercise] = useState("스쿼트");
  const [feedback, setFeedback] = useState("");
  const [vadEnabled, setVadEnabled] = useState(false);

  // 명령어 처리 함수
  const handleCommand = useCallback((action: string) => {
    switch (action) {
      case "pause":
        setIsPlaying(false);
        setFeedback("운동을 멈춥니다.");
        break;
      case "resume":
        setIsPlaying(true);
        setFeedback("운동을 재개합니다.");
        break;
      case "next":
        setFeedback("다음 동작으로 넘어갑니다.");
        break;
      case "previous":
        setFeedback("이전 동작으로 돌아갑니다.");
        break;
      case "faster":
        setFeedback("속도를 빠르게 합니다.");
        break;
      case "slower":
        setFeedback("속도를 느리게 합니다.");
        break;
      default:
        setFeedback(`알 수 없는 명령: ${action}`);
    }
  }, []);

  // Wake Word 훅 - vadEnabled가 true일 때만 작동
  const { mode, lastText, isListening, isSpeaking } = useWakeWord({
    enabled: vadEnabled,
    onWakeDetected: () => {
      setFeedback("네, 말씀하세요.");
      if ("speechSynthesis" in window) {
        const utterance = new SpeechSynthesisUtterance("네, 말씀하세요.");
        utterance.lang = "ko-KR";
        speechSynthesis.speak(utterance);
      }
    },
    onCommand: handleCommand,
    commandTimeout: 10000,
  });

  // VAD 시작 (클릭 후)
  const enableVAD = () => {
    setVadEnabled(true);
    setFeedback("음성 인식 활성화됨. '시선 코치'라고 말해보세요.");
  };

  // 피드백 TTS
  useEffect(() => {
    if (feedback && "speechSynthesis" in window) {
      const utterance = new SpeechSynthesisUtterance(feedback);
      utterance.lang = "ko-KR";
      speechSynthesis.speak(utterance);
    }
  }, [feedback]);

  return (
    <div className="min-h-screen bg-blue-900 p-6 text-white">
      <h1 className="text-2xl font-bold mb-6">{currentExercise}</h1>

      {/* VAD 활성화 버튼 */}
      {!vadEnabled && (
        <div className="bg-yellow-500 text-black rounded-lg p-6 mb-6 text-center">
          <p className="mb-4">음성 제어를 사용하려면 버튼을 클릭하세요</p>
          <Button onClick={enableVAD}>🎤 음성 인식 시작</Button>
        </div>
      )}

      {/* 재생 상태 */}
      <div className="bg-blue-800 rounded-lg p-6 mb-6">
        <div className="text-6xl text-center mb-4">
          {isPlaying ? "▶️" : "⏸️"}
        </div>
        <p className="text-center text-lg">
          {isPlaying ? "운동 중..." : "일시정지"}
        </p>
      </div>

      {/* 음성 상태 표시 */}
      {vadEnabled && (
        <div className="bg-blue-800 rounded-lg p-4 mb-6">
          <div className="flex items-center gap-2 mb-2">
            <div className={`w-3 h-3 rounded-full ${isListening ? "bg-green-500" : "bg-gray-500"}`} />
            <span>음성 감지: {isListening ? "활성" : "비활성"}</span>
          </div>
          <div className="flex items-center gap-2 mb-2">
            <div className={`w-3 h-3 rounded-full ${isSpeaking ? "bg-yellow-500 animate-pulse" : "bg-gray-500"}`} />
            <span>말하기: {isSpeaking ? "감지됨" : "-"}</span>
          </div>
          <div className="flex items-center gap-2">
            <div className={`w-3 h-3 rounded-full ${mode === "command" ? "bg-red-500" : "bg-gray-500"}`} />
            <span>모드: {mode === "idle" ? "대기" : mode === "listening" ? "듣기" : "명령"}</span>
          </div>
        </div>
      )}

      {/* 인식된 텍스트 */}
      {lastText && (
        <div className="bg-blue-800 rounded-lg p-4 mb-6">
          <p className="text-sm text-gray-400">인식된 음성:</p>
          <p className="text-lg">&quot;{lastText}&quot;</p>
        </div>
      )}

      {/* 피드백 */}
      {feedback && (
        <div className="bg-yellow-500 text-black rounded-lg p-4 mb-6">
          <p>{feedback}</p>
        </div>
      )}

      {/* 수동 제어 버튼 */}
      <div className="flex gap-4">
        <Button onClick={() => setIsPlaying(!isPlaying)}>
          {isPlaying ? "멈춤" : "시작"}
        </Button>
      </div>

      {/* 사용 안내 */}
      <div className="mt-6 text-sm text-gray-400">
        <p>💡 &quot;시선 코치&quot;라고 말하면 명령 모드로 진입합니다.</p>
        <p>지원 명령: 멈춤, 시작, 다음, 이전, 빠르게, 느리게</p>
      </div>
    </div>
  );
}
