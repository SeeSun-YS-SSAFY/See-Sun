import { atom } from "jotai";
import { atomWithStorage } from "jotai/utils";

export const test1 = atomWithStorage<number>("test1", 0);