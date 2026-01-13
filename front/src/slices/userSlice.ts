import { createSlice, PayloadAction } from "@reduxjs/toolkit";

interface UserState {
  name: string;
  gender: string;
}

const initialState: UserState = {
  name: "",
  gender: "",
};

const userSlice = createSlice({
  name: "user",
  initialState,
  reducers: {
    setName: (state, action: PayloadAction<string>) => {
      state.name = action.payload;
    },
    setGender: (state, action: PayloadAction<string>) => {
      state.gender = action.payload;
    },
  },
});

export const { setName, setGender } = userSlice.actions;

export default userSlice.reducer;
