"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import { useWebSocketSTT } from "@/hooks/useWebSocketSTT";
import Button from "@/components/common/Button";

/**
 * WebSocket 음성 명령 테스트 페이지
 * - Wake word 없이 바로 명령 실행
 */
export default function ExerciseWSPlayerPage() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [vadEnabled, setVadEnabled] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);
  const logContainerRef = useRef<HTMLDivElement>(null);

  // 로그 추가
  const addLog = useCallback((message: string) => {
    const time = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    setLogs(prev => [...prev.slice(-50), `[${time}] ${message}`]);
  }, []);

  // 스크롤 자동 이동
  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs]);

  // 명령어 처리
  const handleCommand = useCallback((action: string) => {
    addLog(`✅ 명령 실행: ${action}`);
    
    const messages: Record<string, string> = {
      pause: "운동을 멈춥니다.",
      resume: "운동을 재개합니다.",
      next: "다음 동작으로 넘어갑니다.",
      previous: "이전 동작으로 돌아갑니다.",
      faster: "속도를 빠르게 합니다.",
      slower: "속도를 느리게 합니다.",
    };
    
    setFeedback(messages[action] || `명령: ${action}`);
    
    if (action === 'pause') setIsPlaying(false);
    if (action === 'resume') setIsPlaying(true);
    
    // TTS 피드백
    if ("speechSynthesis" in window && messages[action]) {
      const utterance = new SpeechSynthesisUtterance(messages[action]);
      utterance.lang = "ko-KR";
      speechSynthesis.speak(utterance);
    }
  }, [addLog]);

  // WebSocket STT
  const {
    isConnected,
    isRecording,
    isSpeaking,
    isProcessing,
    lastText,
    lastAction,
  } = useWebSocketSTT({
    enabled: vadEnabled,
    onCommand: handleCommand,
    onTranscript: (text) => {
      if (text) addLog(`📝 인식: "${text}"`);
    },
  });

  // 상태 변화 로그
  useEffect(() => {
    if (vadEnabled && isConnected) {
      addLog("🔌 WebSocket 연결됨");
    }
  }, [isConnected, vadEnabled, addLog]);

  useEffect(() => {
    if (isRecording) addLog("🎙️ 녹음 중...");
  }, [isRecording, addLog]);

  useEffect(() => {
    if (isProcessing) addLog("⏳ 분석 중...");
  }, [isProcessing, addLog]);

  return (
    <div className="min-h-screen bg-blue-900 p-4 text-white flex gap-4">
      {/* 왼쪽: 메인 */}
      <div className="flex-1">
        <h1 className="text-2xl font-bold mb-4">🎤 음성 명령 테스트</h1>

        {/* 시작 버튼 */}
        {!vadEnabled && (
          <div className="bg-yellow-500 text-black rounded-lg p-4 mb-4 text-center">
            <p className="mb-2">음성 명령을 시작하려면 버튼을 클릭하세요</p>
            <Button onClick={() => { setVadEnabled(true); addLog("🎤 음성 인식 시작"); }}>
              🎤 음성 인식 시작
            </Button>
          </div>
        )}

        {/* 상태 표시 */}
        {vadEnabled && (
          <div className="bg-blue-800 rounded-lg p-3 mb-4 text-sm">
            <div className="grid grid-cols-2 gap-2">
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isConnected ? "bg-green-500" : "bg-red-500"}`} />
                <span>연결: {isConnected ? "✓" : "✗"}</span>
              </div>
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isSpeaking ? "bg-yellow-500 animate-pulse" : "bg-gray-500"}`} />
                <span>음성: {isSpeaking ? "감지" : "-"}</span>
              </div>
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isRecording ? "bg-red-500 animate-pulse" : "bg-gray-500"}`} />
                <span>녹음: {isRecording ? "중" : "-"}</span>
              </div>
              <div className="flex items-center gap-2">
                <div className={`w-2 h-2 rounded-full ${isProcessing ? "bg-blue-500 animate-pulse" : "bg-gray-500"}`} />
                <span>분석: {isProcessing ? "중" : "-"}</span>
              </div>
            </div>
          </div>
        )}

        {/* 재생 상태 */}
        <div className="bg-blue-800 rounded-lg p-6 mb-4 text-center">
          <div className="text-5xl mb-2">{isPlaying ? "▶️" : "⏸️"}</div>
          <p>{isPlaying ? "운동 중..." : "일시정지"}</p>
        </div>

        {/* 인식 결과 */}
        {lastText && (
          <div className="bg-blue-800 rounded-lg p-3 mb-4">
            <p className="text-xs text-gray-400">마지막 인식:</p>
            <p className="text-lg">&quot;{lastText}&quot;</p>
            {lastAction && <p className="text-sm text-green-400">→ {lastAction}</p>}
          </div>
        )}

        {/* 피드백 */}
        {feedback && (
          <div className="bg-green-600 rounded-lg p-3 mb-4">
            <p>{feedback}</p>
          </div>
        )}

        {/* 사용 가능한 명령어 */}
        <div className="bg-blue-800/50 rounded-lg p-3 mb-4">
          <p className="text-xs text-gray-400 mb-2">사용 가능한 명령어:</p>
          <div className="flex flex-wrap gap-2 text-sm">
            <span className="bg-blue-700 px-2 py-1 rounded">멈춰</span>
            <span className="bg-blue-700 px-2 py-1 rounded">시작</span>
            <span className="bg-blue-700 px-2 py-1 rounded">다음</span>
            <span className="bg-blue-700 px-2 py-1 rounded">이전</span>
            <span className="bg-blue-700 px-2 py-1 rounded">빠르게</span>
            <span className="bg-blue-700 px-2 py-1 rounded">느리게</span>
          </div>
        </div>

        {/* 수동 제어 */}
        <div className="flex gap-2">
          <Button onClick={() => setIsPlaying(!isPlaying)}>
            {isPlaying ? "멈춤" : "시작"}
          </Button>
          {vadEnabled && (
            <Button onClick={() => { setVadEnabled(false); addLog("🔇 음성 인식 중지"); }}>
              🔇 끄기
            </Button>
          )}
        </div>
      </div>

      {/* 오른쪽: 실시간 로그 */}
      <div className="w-80 bg-gray-900 rounded-lg p-3 flex flex-col">
        <div className="flex justify-between items-center mb-2">
          <h2 className="font-bold text-sm">📋 실시간 로그</h2>
          <button 
            onClick={() => setLogs([])}
            className="text-xs text-gray-400 hover:text-white"
          >
            클리어
          </button>
        </div>
        <div 
          ref={logContainerRef}
          className="flex-1 overflow-y-auto text-xs font-mono space-y-1 bg-black/50 rounded p-2 max-h-[70vh]"
        >
          {logs.length === 0 ? (
            <p className="text-gray-500">로그가 없습니다</p>
          ) : (
            logs.map((log, i) => (
              <div key={i} className="text-gray-300 break-all">
                {log}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
