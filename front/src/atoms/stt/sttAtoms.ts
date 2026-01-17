import { atom } from "jotai";

export type RecordingStatus = "off" | "recording";
export type UploadStatus = "idle" | "uploading" | "success" | "error";

export const recordingStatusAtom = atom<RecordingStatus>("off");
export const uploadStatusAtom = atom<UploadStatus>("idle");

export const sttTextAtom = atom<string>("");
export const sttErrorAtom = atom<string>("");

export const lastAudioBlobAtom = atom<Blob | null>(null);

// write-only setter atoms for reliable typing with useSetAtom
export const setRecordingStatusAtom = atom(
  null,
  (_get, set, value: RecordingStatus) => set(recordingStatusAtom, value)
);

export const setUploadStatusAtom = atom(
  null,
  (_get, set, value: UploadStatus) => set(uploadStatusAtom, value)
);

export const setSttTextAtom = atom(
  null,
  (_get, set, value: string) => set(sttTextAtom, value)
);

export const setSttErrorAtom = atom(
  null,
  (_get, set, value: string) => set(sttErrorAtom, value)
);

export const setLastAudioBlobAtom = atom(
  null,
  (_get, set, value: Blob | null) => set(lastAudioBlobAtom, value)
);
