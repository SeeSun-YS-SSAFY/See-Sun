import { configureStore } from "@reduxjs/toolkit";
import userReducer from "../slices/userSlice"; // 나중에 userSlice를 생성

export const store = configureStore({
  reducer: {
    user: userReducer, // 나중에 userSlice의 리듀서 사용
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
