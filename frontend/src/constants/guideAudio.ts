
const STT_BASE_URL = "http://127.0.0.1:8000/media/prefix/"

export const GUIDE_AUDIO: Record<string, string | undefined> = {
  "/": `${STT_BASE_URL}main.mp3`,
  "/auth/callback": "",

  "/exercise": `${STT_BASE_URL}exercise.mp3`,

  "/exercise/[ex_type]": "notyet",
  "/exercise/[ex_type]/[sport_pk]": "notyet",

  "/exercise/custom": `${STT_BASE_URL}exercise_custom.mp3`,
  "/exercise/custom/make_exercise": `${STT_BASE_URL}exercise_custom_make_exercise.mp3`,
  "/exercise/custom/make_exercise/category": `${STT_BASE_URL}exercise_custom_make_exercise_category.mp3`,
  "/exercise/custom/make_exercise/category/[custom_ex_type]": "notyet",
  "/exercise/custom/make_routine": `${STT_BASE_URL}exercise_custom_make_routine.mp3`,

  "/exercise/custom/routine/[id]": "notyet",
  "/exercise/custom/routine/[id]/edit": "notyet",

  "/exercise/frequent": "notyet",
  "/exercise/frequent/[sport_pk]": "notyet",

  "/exercise/routine": `${STT_BASE_URL}exercise_routine.mp3`,
  "/exercise/single": `${STT_BASE_URL}exercise_single.mp3`,

  "/login": `${STT_BASE_URL}login.mp3`,
  "/login/generallogin": `${STT_BASE_URL}login_generallogin.mp3`,

  "/mypage": `${STT_BASE_URL}mypage.mp3`,
  "/signup": `${STT_BASE_URL}signup.mp3`,
  
  "/userinfo": "",
  "/userinfo/birth": `${STT_BASE_URL}userinfo_birth.mp3`,
  "/userinfo/gender": `${STT_BASE_URL}userinfo_gender.mp3`,
  "/userinfo/height": `${STT_BASE_URL}userinfo_height.mp3`,
  "/userinfo/name": `${STT_BASE_URL}userinfo_name.mp3`,
  "/userinfo/phone": `${STT_BASE_URL}userinfo_phone.mp3`,
  "/userinfo/weight": `${STT_BASE_URL}userinfo_weight.mp3`,
};
