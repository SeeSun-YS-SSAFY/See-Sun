"use client";

import { useState, useEffect, useCallback } from "react";
import { useListenSTT, useCommandSTT } from "@/hooks/stt";
import Button from "@/components/common/Button";

/**
 * 운동 재생 페이지 - Wake Word 음성 제어 지원
 * "시선 코치" (Listen Mode) → 명령 모드 (Command Mode) → "멈춤", "다음" 등 실행
 */
export default function ExercisePlayerPage() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentExercise] = useState("스쿼트");
  const [feedback, setFeedback] = useState("");

  // 모드 상태: idle(꺼짐) | listening(예약어 대기) | command(명령 대기)
  const [sttMode, setSttMode] = useState<"idle" | "listening" | "command">("idle");

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
        setFeedback(`명령 확인: ${action}`);
    }

    // 명령 처리 후 다시 듣기 모드로 복귀 (또는 연속 명령을 위해 커맨드 유지할 수도 있음)
    // 기획상 '한 번 명령 후 복귀'라면:
    setSttMode("listening");
  }, []);

  // 1. Listen Mode Hook (예약어 감지)
  const listenSTT = useListenSTT({
    onWakeDetected: () => {
      setFeedback("네, 말씀하세요.");
      setSttMode("command"); // 명령 모드로 전환

      if ("speechSynthesis" in window) {
        const utterance = new SpeechSynthesisUtterance("네, 말씀하세요.");
        utterance.lang = "ko-KR";
        speechSynthesis.speak(utterance);
      }
    }
  });

  // 2. Command Mode Hook (명령어 인식)
  const commandSTT = useCommandSTT({
    onAction: (action) => {
      handleCommand(action);
    },
    onUnknown: (text) => {
      setFeedback("다시 말씀해주세요.");
    },
    onError: () => {
      setFeedback("오류가 발생했습니다. 다시 시도해주세요.");
      setSttMode("listening"); // 오류 시 다시 듣기 모드로
    }
  });

  // 모드에 따라 훅 제어 (useEffect로 토글)
  // Hooks는 항상 호출되지만, toggleRecording으로 제어
  // 모드에 따른 제어
  useEffect(() => {
    if (sttMode === "listening") {
      if (!listenSTT.isListening) listenSTT.start();
      if (commandSTT.isActive) commandSTT.deactivate();
    } else if (sttMode === "command") {
      if (listenSTT.isListening) listenSTT.stop();
      if (!commandSTT.isActive) commandSTT.activate();
    } else {
      // Idle
      if (listenSTT.isListening) listenSTT.stop();
      if (commandSTT.isActive) commandSTT.deactivate();
    }
  }, [sttMode, listenSTT, commandSTT]);


  // VAD 시작 (클릭 후)
  const enableVAD = () => {
    setSttMode("listening");
    setFeedback("음성 인식 활성화됨. '시선 코치'라고 말해보세요.");
  };

  // 피드백 TTS
  useEffect(() => {
    if (feedback && "speechSynthesis" in window) {
      // 이미 말하고 있는 게 있으면 취소하고 (옵션)
      // speechSynthesis.cancel(); 
      const utterance = new SpeechSynthesisUtterance(feedback);
      utterance.lang = "ko-KR";
      speechSynthesis.speak(utterance);
    }
  }, [feedback]);

  return (
    <div className="min-h-screen bg-blue-900 p-6 text-white">
      <h1 className="text-2xl font-bold mb-6">{currentExercise}</h1>

      {/* VAD 활성화 버튼 */}
      {sttMode === "idle" && (
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
      {sttMode !== "idle" && (
        <div className="bg-blue-800 rounded-lg p-4 mb-6">
          <div className="flex items-center gap-2 mb-2">
            <div className="w-3 h-3 rounded-full bg-green-500" />
            <span>음성 제어: {sttMode === "listening" ? "호출 대기 중" : "명령 듣는 중"}</span>
          </div>
          <div className="flex items-center gap-2">
            {/* 현재 활성화된 훅의 상태 표시 */}
            <div className={`w-3 h-3 rounded-full ${(sttMode === "listening" ? listenSTT.isProcessing : commandSTT.isProcessing) ? "bg-blue-500 animate-pulse" : "bg-gray-500"}`} />
            <span>분석: {(sttMode === "listening" ? listenSTT.isProcessing : commandSTT.isProcessing) ? "중" : "-"}</span>
          </div>
        </div>
      )}

      {/* 인식된 텍스트 (Command 모드일 때만 결과가 옴) */}
      {commandSTT.transcript && (
        <div className="bg-blue-800 rounded-lg p-4 mb-6">
          <p className="text-sm text-gray-400">인식된 명령:</p>
          <p className="text-lg">&quot;{commandSTT.transcript}&quot;</p>
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
        {sttMode !== "idle" && (
          <Button onClick={() => setSttMode("idle")}>
            🔇 끄기
          </Button>
        )}
      </div>

      {/* 사용 안내 */}
      <div className="mt-6 text-sm text-gray-400">
        <p>💡 &quot;시선 코치&quot;라고 말하면 명령 모드로 진입합니다.</p>
        <p>지원 명령: 멈춤, 시작, 다음, 이전, 빠르게, 느리게</p>
      </div>
    </div>
  );
}
