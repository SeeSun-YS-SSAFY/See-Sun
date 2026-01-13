export default function Home() {
  return (
    <main className="flex flex-col items-center">
      {/* Top 영역 (로고 + 타이틀) */}
      <header className="w-full max-w-[320px] flex items-center justify-center gap-3 mb-10">
        {/* 로고 자리 (여기에 Image 컴포넌트로 교체) */}

            <div className="flex items-center gap-1">
            <img
                src="/Seesunlogo.png"
                alt="시선 로고입니다"
                className="w-20"
            />

            <h1 className="text-yellow-300 text-3xl font-extrabold tracking-wide">
                See:Sun
            </h1>
            </div>
      </header>

      {/* 2x2 카드 메뉴 */}
      <section className="w-full max-w-[320px] grid grid-cols-2 gap-6">
        <MenuCard title="식단" icon={<ForkKnifeIcon />} />
        <MenuCard title="운동" icon={<DumbbellIcon />} />
        <MenuCard title="약" icon={<PillIcon />} />
        <MenuCard title={"마이\n페이지"} icon={<UserIcon />} />
      </section>
    </main>
  );
}

function MenuCard({
  title,
  icon,
}: {
  title: string;
  icon: React.ReactNode;
}) {
  return (
    <button
      type="button"
      className="
        h-[170px] w-full
        rounded-2xl bg-yellow-200
        shadow-lg shadow-black/25
        flex flex-col items-center justify-center gap-4
        text-indigo-900
        active:scale-[0.99]
        focus:outline-none focus:ring-4 focus:ring-yellow-200/60
      "
    >
      <div className="text-3xl font-extrabold whitespace-pre-line text-center leading-tight">
        {title}
      </div>

      <div className="text-indigo-900">{icon}</div>
    </button>
  );
}

/* 아래 아이콘들은 의존성 없이 그냥 쓰려고 간단 SVG로 넣어둠 */

function ForkKnifeIcon() {
  return (
    <svg width="52" height="52" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M6 2c.6 0 1 .4 1 1v7a2 2 0 0 1-1 1.7V22H4V11.7A2 2 0 0 1 3 10V3c0-.6.4-1 1-1h2Zm0 2H5v6a.5.5 0 0 0 1 0V4ZM10 2h2v20h-2V2Zm8 0c1.1 0 2 .9 2 2v6c0 1.4-1 2.6-2.3 2.9V22h-2V2h2Z" />
    </svg>
  );
}

function DumbbellIcon() {
  return (
    <svg width="58" height="58" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M4 9h2V7a1 1 0 0 1 2 0v10a1 1 0 0 1-2 0v-2H4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1Zm16 0a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-2v2a1 1 0 0 1-2 0V7a1 1 0 0 1 2 0v2h2ZM9 10h6v4H9v-4Z" />
    </svg>
  );
}

function PillIcon() {
  return (
    <svg width="58" height="58" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M16.2 2.8a6 6 0 0 1 0 8.5l-5 5a6 6 0 1 1-8.5-8.5l5-5a6 6 0 0 1 8.5 0Zm-7.1 1.4-5 5a4 4 0 1 0 5.7 5.7l2-2-5.7-5.7Zm4.1 4.1 2-2a4 4 0 0 0-5.7 0l-.7.7 5.7 5.7.7-.7a4 4 0 0 0 0-5.7Z" />
    </svg>
  );
}

function UserIcon() {
  return (
    <svg width="56" height="56" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M12 12a5 5 0 1 0-5-5 5 5 0 0 0 5 5Zm0 2c-4.4 0-8 2.2-8 5v1h16v-1c0-2.8-3.6-5-8-5Z" />
    </svg>
  );
}
