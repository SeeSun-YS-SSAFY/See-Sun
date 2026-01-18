import { useState, useRef, useCallback, useEffect } from 'react';
import { useVAD } from './useVAD';

interface UseWebSocketSTTOptions {
  enabled?: boolean;
  onCommand?: (action: string) => void;
  onTranscript?: (text: string) => void;
}

/**
 * Float32Array를 Int16 PCM으로 변환
 */
function floatTo16BitPCM(input: Float32Array[]): ArrayBuffer {
  // 전체 길이 계산
  let totalLength = 0;
  for (const chunk of input) {
    totalLength += chunk.length;
  }
  
  const output = new Int16Array(totalLength);
  let offset = 0;
  
  for (const chunk of input) {
    for (let i = 0; i < chunk.length; i++) {
      const s = Math.max(-1, Math.min(1, chunk[i]));
      output[offset++] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
  }
  
  return output.buffer;
}

export function useWebSocketSTT(options: UseWebSocketSTTOptions = {}) {
  const { enabled = true, onCommand, onTranscript } = options;
  
  const [isConnected, setIsConnected] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [lastText, setLastText] = useState('');
  const [lastAction, setLastAction] = useState<string | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);
  
  const wsRef = useRef<WebSocket | null>(null);

  // 콜백 refs
  const onCommandRef = useRef(onCommand);
  const onTranscriptRef = useRef(onTranscript);
  
  useEffect(() => {
    onCommandRef.current = onCommand;
    onTranscriptRef.current = onTranscript;
  }, [onCommand, onTranscript]);

  // WebSocket 연결
  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;
    
    const wsUrl = `ws://localhost:8000/ws/stt/`;
    console.log('[WS STT] 연결 시도:', wsUrl);
    
    const ws = new WebSocket(wsUrl);
    
    ws.onopen = () => {
      console.log('[WS STT] 연결됨');
      setIsConnected(true);
    };
    
    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      console.log('[WS STT] 수신:', data);
      
      if (data.type === 'result') {
        setLastText(data.message || '');
        setLastAction(data.action || null);
        setIsProcessing(false);
        
        onTranscriptRef.current?.(data.message || '');
        
        if (data.action) {
          console.log('[WS STT] 명령 실행:', data.action);
          onCommandRef.current?.(data.action);
        }
      } else if (data.type === 'error') {
        console.error('[WS STT] 서버 오류:', data.message);
        setIsProcessing(false);
      }
    };
    
    ws.onclose = () => {
      console.log('[WS STT] 연결 해제');
      setIsConnected(false);
    };
    
    ws.onerror = (error) => {
      console.error('[WS STT] 오류:', error);
    };
    
    wsRef.current = ws;
  }, []);

  // 연결 해제
  const disconnect = useCallback(() => {
    wsRef.current?.close();
    wsRef.current = null;
    setIsConnected(false);
  }, []);

  // PCM 데이터 전송
  const sendPCMData = useCallback((buffer: Float32Array[], sampleRate: number) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    if (buffer.length === 0) return;
    
    // Float32 → Int16 PCM 변환
    const pcmData = floatTo16BitPCM(buffer);
    
    console.log('[WS STT] PCM 전송:', pcmData.byteLength, 'bytes, 샘플레이트:', sampleRate);
    
    // 메타데이터 전송
    wsRef.current.send(JSON.stringify({ 
      command: 'audio',
      sampleRate: sampleRate,
      format: 'pcm16'
    }));
    
    // PCM 데이터 전송
    wsRef.current.send(pcmData);
    
    // 처리 요청
    wsRef.current.send(JSON.stringify({ command: 'process' }));
    
    setIsProcessing(true);
  }, []);

  // VAD 연동 (상시 청취 + 순환 버퍼)
  const vad = useVAD({
    enabled: enabled && isConnected,
    threshold: -45, // 민감도 조정
    onSpeechStart: () => {
      console.log('[WS STT] 음성 감지 시작');
      setIsRecording(true);
    },
    onSpeechEnd: (buffer, sampleRate) => {
      console.log('[WS STT] 음성 감지 종료, 버퍼:', buffer.length, '청크');
      setIsRecording(false);
      
      // 바로 전송 (isProcessing 체크 제거 - closure 문제)
      sendPCMData(buffer, sampleRate);
    },
  });

  // enabled 변경 시 자동 연결/해제
  useEffect(() => {
    if (enabled) {
      connect();
    } else {
      disconnect();
    }
    
    return () => disconnect();
  }, [enabled]);

  return {
    isConnected,
    isRecording,
    isSpeaking: vad.isSpeaking,
    isProcessing,
    lastText,
    lastAction,
    connect,
    disconnect,
  };
}
