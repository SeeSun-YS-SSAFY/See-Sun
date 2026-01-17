import { http, HttpResponse } from "msw";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL!;

type LoginBody = {
  phone_number?: string;
  pin_number?: string;
};

type PlaylistItem = {
  exercise_id: number;
  exercise_name: string;
  sequence_no: number;
  set_count: number;
  reps_count: number;
};

type Playlist = {
  playlist_id: string;
  title: string;
  items: PlaylistItem[];
};

let playlists: Playlist[] = [
  {
    playlist_id: "1",
    title: "아침 스트레칭 루틴",
    items: [
      {
        exercise_id: 1,
        exercise_name: "스쿼트",
        sequence_no: 1,
        set_count: 3,
        reps_count: 10,
      },
      {
        exercise_id: 2,
        exercise_name: "푸시업",
        sequence_no: 2,
        set_count: 3,
        reps_count: 12,
      },
      {
        exercise_id: 3,
        exercise_name: "플랭크",
        sequence_no: 3,
        set_count: 3,
        reps_count: 45,
      },
    ],
  },
  {
    playlist_id: "2",
    title: "상체 근력 루틴",
    items: [
      {
        exercise_id: 4,
        exercise_name: "풀업",
        sequence_no: 1,
        set_count: 3,
        reps_count: 8,
      },
    ],
  },
  {
    playlist_id: "3",
    title: "하체 + 코어 루틴",
    items: [
      {
        exercise_id: 5,
        exercise_name: "런지",
        sequence_no: 1,
        set_count: 3,
        reps_count: 10,
      },
    ],
  },
];

const nextId = () => String(Date.now());

export const handlers = [
  // Login
  http.post(`${API_BASE}/auth/login`, async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as LoginBody;

    const phone = String(body.phone_number ?? "");
    const code = String(body.pin_number ?? "");

    if (phone === "01012345678" && code === "1234") {
      return HttpResponse.json(
        {
          accessToken: "mock_access_token_01012345678",
          user: { phone },
        },
        { status: 200 }
      );
    }

    return HttpResponse.json({ message: "Auth failed" }, { status: 401 });
  }),

  // List playlists
  http.get(`${API_BASE}/exercises/playlist/`, () => {
    return HttpResponse.json(
      playlists.map(({ playlist_id, title }) => ({ playlist_id, title })),
      { status: 200 }
    );
  }),
  http.get(`${API_BASE}/exercises/playlist`, () => {
    return HttpResponse.json(
      playlists.map(({ playlist_id, title }) => ({ playlist_id, title })),
      { status: 200 }
    );
  }),

  // Playlist detail
  http.get(`${API_BASE}/exercises/playlist/:playlist_id/`, ({ params }) => {
    const playlistId = String(params.playlist_id ?? "");
    const found = playlists.find((p) => p.playlist_id === playlistId);
    if (!found) {
      return HttpResponse.json({ message: "Not found" }, { status: 404 });
    }
    return HttpResponse.json(found, { status: 200 });
  }),
  http.get("/exercises/playlist/:playlist_id/", ({ params }) => {
    const playlistId = String(params.playlist_id ?? "");
    const found = playlists.find((p) => p.playlist_id === playlistId);
    if (!found) {
      return HttpResponse.json({ message: "Not found" }, { status: 404 });
    }
    return HttpResponse.json(found, { status: 200 });
  }),

  // Create playlist
  http.post(`${API_BASE}/exercises/playlist/`, async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as {
      title?: string;
      items?: PlaylistItem[];
    };

    const title = String(body.title ?? "").trim();
    const items = Array.isArray(body.items) ? body.items : [];

    if (!title) {
      return HttpResponse.json(
        { message: "Title is required" },
        { status: 400 }
      );
    }
    if (items.length === 0) {
      return HttpResponse.json(
        { message: "At least one item is required" },
        { status: 400 }
      );
    }

    const newPlaylist: Playlist = {
      playlist_id: nextId(),
      title,
      items,
    };
    playlists = [newPlaylist, ...playlists];

    return HttpResponse.json(newPlaylist, { status: 201 });
  }),

  // Update playlist (title/items)
  http.patch(`${API_BASE}/exercises/playlist/edit/`, async ({ request }) => {
    const body = (await request.json().catch(() => ({}))) as {
      playlist_id?: string;
      title?: string;
      items?: PlaylistItem[];
    };

    const playlistId = String(body.playlist_id ?? "");
    const title = body.title?.trim();
    const items = Array.isArray(body.items) ? body.items : undefined;

    const index = playlists.findIndex((p) => p.playlist_id === playlistId);
    if (index === -1) {
      return HttpResponse.json({ message: "Not found" }, { status: 404 });
    }
    if (title !== undefined && title.length === 0) {
      return HttpResponse.json(
        { message: "Title is required" },
        { status: 400 }
      );
    }

    const updated: Playlist = {
      ...playlists[index],
      ...(title ? { title } : {}),
      ...(items ? { items } : {}),
    };
    playlists = [
      ...playlists.slice(0, index),
      updated,
      ...playlists.slice(index + 1),
    ];

    return HttpResponse.json(updated, { status: 200 });
  }),

  // Delete playlist
  http.delete(`${API_BASE}/exercises/playlist/:playlist_iddelete/`, ({ params }) => {
    const playlistId = String(params.playlist_id ?? "");
    const exists = playlists.some((p) => p.playlist_id === playlistId);
    playlists = playlists.filter((p) => p.playlist_id !== playlistId);
    if (!exists) {
      return HttpResponse.json({ message: "Not found" }, { status: 404 });
    }
    return HttpResponse.json(null, { status: 204 });
  }),
];
