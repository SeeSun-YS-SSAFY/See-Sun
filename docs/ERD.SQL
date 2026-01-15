// See-Sun Database ERD (DBML format)
// Generated: 2026-01-15
// PK Strategy: UUID (API 노출) / BigInt (내부용)

// ===== 사용자 관련 테이블 =====

Table users {
  user_id uuid [pk, default: `uuid_generate_v4()`]
  username varchar(150) [unique, not null]
  password varchar(128) [not null]
  name varchar(255)
  phone_number varchar(20) [unique, not null]
  pin_hash varchar(255) [not null]
  birthdate date [not null, note: '필수 정보']
  gender varchar(1) [not null, note: 'M|F, 필수 정보']
  height_cm integer [not null, note: '필수 정보, 50~250']
  weight_kg integer [not null, note: '필수 정보, 20~300']
  is_profile_completed boolean [default: false]
  date_joined timestamp [default: `CURRENT_TIMESTAMP`]
  last_login timestamp
  
  is_active boolean [default: true]
  is_staff boolean [default: false]
  is_superuser boolean [default: false]
  
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  updated_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    phone_number
    created_at
    username
  }
}

Table user_auth_provider {
  auth_id bigint [pk, increment]
  user_id uuid [not null, ref: > users.user_id]
  provider varchar(20) [not null, note: 'LOCAL|GOOGLE']
  provider_subject varchar(255) [note: 'google sub id']
  provider_email varchar(255)
  linked_at timestamp [default: `CURRENT_TIMESTAMP`]
  last_login_at timestamp
  
  indexes {
    user_id
    (provider, provider_subject)
    (user_id, provider) [unique]
  }
}

Table user_devices {
  device_id uuid [pk, default: `uuid_generate_v4()`]
  user_id uuid [not null, ref: > users.user_id]
  device_hash varchar(64) [unique, not null]
  device_name varchar(255)
  is_verified boolean [default: false]
  verified_at timestamp
  last_used_at timestamp
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    user_id
    device_hash
    (user_id, is_verified)
  }
}

Table sms_verification {
  verification_id bigint [pk, increment]
  phone_number varchar(20) [not null]
  code varchar(6) [not null]
  purpose varchar(20) [not null, note: 'DEVICE_VERIFY|PASSWORD_RESET']
  verification_token varchar(255) [unique]
  expires_at timestamp [not null]
  verified boolean [default: false]
  attempt_count integer [default: 0]
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    phone_number
    verification_token
    expires_at
  }
}

Table refresh_token_blacklist {
  id bigint [pk, increment]
  token_jti varchar(255) [unique, not null]
  user_id uuid [ref: > users.user_id]
  expires_at timestamp [not null]
  blacklisted_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    token_jti
    expires_at
    user_id
  }
}

// ===== 운동 관련 테이블 =====

Table exercise_category {
  category_id integer [pk, increment]
  display_name varchar(255) [not null]
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
}

Table exercise {
  exercise_id integer [pk, increment]
  category_id integer [ref: > exercise_category.category_id]
  exercise_name varchar(255) [not null]
  exercise_description text
  first_description text
  main_form text
  form_description text
  stay_form text
  fixed_form text
  exercise_guide_text text [note: 'TTS text base']
  is_active boolean [default: true]
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  updated_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    category_id
    is_active
    exercise_name
  }
}

Table exercise_media {
  media_id bigint [pk, increment]
  exercise_id integer [not null, ref: > exercise.exercise_id]
  media_type varchar(20) [not null, note: 'PICTOGRAM|GUIDE_AUDIO|TTS_PREGEN']
  locale varchar(10) [default: 'ko-KR']
  s3_key varchar(512)
  url varchar(1024)
  duration_ms integer
  checksum varchar(64)
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    exercise_id
    media_type
    locale
  }
}

Table user_favorites {
  favorite_id bigint [pk, increment]
  user_id uuid [not null, ref: > users.user_id]
  exercise_id integer [not null, ref: > exercise.exercise_id]
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    user_id
    exercise_id
    (user_id, exercise_id) [unique]
  }
}

// ===== 플레이리스트 관련 테이블 =====

Table playlists {
  playlist_id uuid [pk, default: `uuid_generate_v4()`]
  user_id uuid [not null, ref: > users.user_id]
  mode varchar(20) [not null, note: 'SINGLE|ROUTINE|CUSTOM']
  title varchar(255) [not null]
  status varchar(20) [default: 'ACTIVE', note: 'ACTIVE|ARCHIVED']
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  updated_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    user_id
    status
    (user_id, status)
  }
}

Table playlist_items {
  playlist_item_id bigint [pk, increment]
  playlist_id uuid [not null, ref: > playlists.playlist_id]
  exercise_id integer [not null, ref: > exercise.exercise_id]
  sequence_no integer [not null]
  set_count integer [not null]
  reps_count integer
  duration_sec integer
  rest_sec integer [default: 10]
  cue_overrides jsonb
  
  indexes {
    playlist_id
    exercise_id
    (playlist_id, sequence_no) [unique]
  }
}

// ===== 운동 세션/로그 관련 테이블 =====

Table exercise_sessions {
  session_id uuid [pk, default: `uuid_generate_v4()`]
  user_id uuid [not null, ref: > users.user_id]
  playlist_id uuid [ref: > playlists.playlist_id, note: 'nullable (단일 운동 가능)']
  mode varchar(20) [not null, note: 'SINGLE|ROUTINE']
  started_at timestamp [not null]
  ended_at timestamp
  duration_ms integer
  is_valid boolean [default: true]
  abnormal_end_reason varchar(20) [note: 'NORMAL|APP_KILLED|CRASH|UNKNOWN']
  device_hash varchar(64)
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    user_id
    playlist_id
    started_at
    (user_id, started_at)
    is_valid
  }
}

Table exercise_session_items {
  session_item_id bigint [pk, increment]
  session_id uuid [not null, ref: > exercise_sessions.session_id]
  exercise_id integer [not null, ref: > exercise.exercise_id]
  playlist_item_id bigint [note: 'nullable']
  sequence_no integer [not null]
  started_at timestamp
  ended_at timestamp
  duration_ms integer
  is_skipped boolean [default: false]
  skip_reason varchar(20) [note: 'LOAD_FAIL|USER_SKIP|UNKNOWN']
  rest_sec integer [default: 10]
  
  indexes {
    session_id
    exercise_id
    (session_id, sequence_no)
  }
}

Table exercise_session_events {
  event_id bigint [pk, increment]
  session_id uuid [not null, ref: > exercise_sessions.session_id]
  session_item_id bigint [ref: > exercise_session_items.session_item_id, note: 'nullable']
  event_time_ms bigint [not null, note: 'relative to session start']
  event_type varchar(50) [not null, note: 'PLAY|PAUSE|SEEK_SEGMENT|SPEED_CHANGE|VUI_WAKE|VUI_CMD|LOAD_FAIL|...']
  payload jsonb [note: 'flexible schema']
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  
  indexes {
    session_id
    session_item_id
    event_type
    (session_id, event_time_ms)
  }
}

Table exercise_log_objects {
  log_object_id bigint [pk, increment]
  session_id uuid [unique, not null, ref: - exercise_sessions.session_id]
  s3_key varchar(512) [unique, not null]
  content_type varchar(20) [note: 'json|ndjson|gzip']
  bytes bigint
  checksum varchar(64)
  upload_status varchar(20) [default: 'PENDING', note: 'PENDING|UPLOADED|FAILED']
  retry_count integer [default: 0]
  last_error_code varchar(50)
  last_error_at timestamp
  created_at timestamp [default: `CURRENT_TIMESTAMP`]
  uploaded_at timestamp
  
  indexes {
    session_id
    upload_status
    s3_key
  }
}