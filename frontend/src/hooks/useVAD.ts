import { useState, useRef, useCallback, useEffect } from 'react';

interface UseVADOptions {
  threshold?: number;
  onSpeechStart?: () => void;
  onSpeechEnd?: (buffer: Float32Array[], sampleRate: number) => void;
  enabled?: boolean;
}

interface AudioBufferData {
  buffer: Float32Array[];
  sampleRate: number;
}

/**
 * PCM 기반 VAD + 순환 버퍼
 * - 마이크 상시 열림
 * - 0.5초 순환 버퍼 유지 (음성 시작 전)
 * - VAD 감지 시 버퍼 + 이후 녹음 반환
 */
export function useVAD(options: UseVADOptions = {}) {
  const {
    threshold = -50,
    onSpeechStart,
    onSpeechEnd,
    enabled = true,
  } = options;

  const [isSpeaking, setIsSpeaking] = useState(false);
  const [isActive, setIsActive] = useState(false);
  
  const streamRef = useRef<MediaStream | null>(null);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  
  // 순환 버퍼 (0.5초 - 음성 시작 전 버퍼)
  const circularBufferRef = useRef<Float32Array[]>([]);
  const maxBufferSeconds = 0.5;
  const sampleRateRef = useRef(16000);
  
  // 현재 녹음 버퍼
  const recordingBufferRef = useRef<Float32Array[]>([]);
  const isRecordingRef = useRef(false);
  
  // 음성 감지 상태
  const speakingRef = useRef(false);
  const silenceTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const waitingForSilenceRef = useRef(false);  // 타임아웃 대기 중 플래그

  // 콜백 refs
  const onSpeechStartRef = useRef(onSpeechStart);
  const onSpeechEndRef = useRef(onSpeechEnd);
  
  useEffect(() => {
    onSpeechStartRef.current = onSpeechStart;
    onSpeechEndRef.current = onSpeechEnd;
  }, [onSpeechStart, onSpeechEnd]);

  // 순환 버퍼에 데이터 추가
  const addToCircularBuffer = useCallback((data: Float32Array) => {
    const maxChunks = Math.ceil((sampleRateRef.current * maxBufferSeconds) / data.length);
    circularBufferRef.current.push(new Float32Array(data));
    
    while (circularBufferRef.current.length > maxChunks) {
      circularBufferRef.current.shift();
    }
  }, []);

  // 순환 버퍼 가져오기
  const getCircularBuffer = useCallback((): Float32Array[] => {
    return [...circularBufferRef.current];
  }, []);

  // 녹음 버퍼 가져오기 (순환 버퍼 + 현재 녹음)
  const getRecordingBuffer = useCallback((): AudioBufferData => {
    const preBuffer = getCircularBuffer();
    const currentBuffer = recordingBufferRef.current;
    
    return {
      buffer: [...preBuffer, ...currentBuffer],
      sampleRate: sampleRateRef.current
    };
  }, [getCircularBuffer]);

  // RMS 볼륨 계산
  const calculateRMS = useCallback((data: Float32Array): number => {
    let sum = 0;
    for (let i = 0; i < data.length; i++) {
      sum += data[i] * data[i];
    }
    const rms = Math.sqrt(sum / data.length);
    return 20 * Math.log10(rms + 0.0001); // dB 변환
  }, []);

  const start = useCallback(async () => {
    if (isActive) return;
    
    console.log('[VAD] 시작...');
    
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ 
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        }
      });
      streamRef.current = stream;
      
      const audioContext = new AudioContext({ sampleRate: 16000 });
      audioContextRef.current = audioContext;
      sampleRateRef.current = audioContext.sampleRate;
      
      const source = audioContext.createMediaStreamSource(stream);
      
      // 분석용
      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 2048;
      analyserRef.current = analyser;
      
      // PCM 데이터 캡처용
      const processor = audioContext.createScriptProcessor(4096, 1, 1);
      processorRef.current = processor;
      
      processor.onaudioprocess = (e) => {
        const inputData = e.inputBuffer.getChannelData(0);
        const volume = calculateRMS(inputData);
        
        // 항상 순환 버퍼에 추가
        addToCircularBuffer(inputData);
        
        // 녹음 중이면 녹음 버퍼에도 추가
        if (isRecordingRef.current) {
          recordingBufferRef.current.push(new Float32Array(inputData));
        }
        
        // 음성 감지
        const isSpeakingNow = volume > threshold;
        
        if (isSpeakingNow && !speakingRef.current) {
          // 말하기 시작
          speakingRef.current = true;
          isRecordingRef.current = true;
          waitingForSilenceRef.current = false;  // 리셋
          recordingBufferRef.current = [];
          setIsSpeaking(true);
          
          if (silenceTimeoutRef.current) {
            clearTimeout(silenceTimeoutRef.current);
            silenceTimeoutRef.current = null;
          }
          
          console.log('[VAD] 말하기 시작');
          onSpeechStartRef.current?.();
        } else if (!isSpeakingNow && speakingRef.current && !waitingForSilenceRef.current) {
          // 말하기 종료 (딜레이) - 처음 한 번만 실행
          waitingForSilenceRef.current = true;
          
          silenceTimeoutRef.current = setTimeout(() => {
            speakingRef.current = false;
            isRecordingRef.current = false;
            waitingForSilenceRef.current = false;
            setIsSpeaking(false);
            
            // 버퍼 복사 후 콜백에 전달
            const preBuffer = [...circularBufferRef.current];
            const recordBuffer = [...recordingBufferRef.current];
            const fullBuffer = [...preBuffer, ...recordBuffer];
            const sr = sampleRateRef.current;
            
            console.log('[VAD] 말하기 종료, 버퍼:', fullBuffer.length, '청크');
            onSpeechEndRef.current?.(fullBuffer, sr);
            
            // 녹음 버퍼 초기화
            recordingBufferRef.current = [];
            silenceTimeoutRef.current = null;
          }, 500);
        } else if (isSpeakingNow && waitingForSilenceRef.current) {
          // 타임아웃 대기 중 다시 말하기 시작 - 타임아웃 취소
          waitingForSilenceRef.current = false;
          if (silenceTimeoutRef.current) {
            clearTimeout(silenceTimeoutRef.current);
            silenceTimeoutRef.current = null;
          }
        }
      };
      
      source.connect(analyser);
      analyser.connect(processor);
      processor.connect(audioContext.destination);
      
      setIsActive(true);
      console.log('[VAD] 활성화 완료 (상시 청취 모드)');
    } catch (error) {
      console.error('[VAD] 시작 실패:', error);
    }
  }, [isActive, threshold, calculateRMS, addToCircularBuffer]);

  const stop = useCallback(() => {
    console.log('[VAD] 정지');
    
    if (silenceTimeoutRef.current) {
      clearTimeout(silenceTimeoutRef.current);
    }
    
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current = null;
    }
    
    if (analyserRef.current) {
      analyserRef.current.disconnect();
      analyserRef.current = null;
    }
    
    if (audioContextRef.current) {
      audioContextRef.current.close();
      audioContextRef.current = null;
    }
    
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => track.stop());
      streamRef.current = null;
    }
    
    circularBufferRef.current = [];
    recordingBufferRef.current = [];
    setIsActive(false);
    setIsSpeaking(false);
  }, []);

  useEffect(() => {
    if (enabled) {
      start();
    } else {
      stop();
    }
    
    return () => stop();
  }, [enabled]);

  return {
    isSpeaking,
    isActive,
    start,
    stop,
    getRecordingBuffer,
    sampleRate: sampleRateRef.current,
  };
}
