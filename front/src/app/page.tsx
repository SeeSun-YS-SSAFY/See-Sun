import Icon from "@/components/common/Icon";
import Image from "next/image";

export default function Home() {
  return (
    <div className="bg-blue-500 h-screen flex flex-col items-center justify-center py-15">
      <div className="flex items-center gap-1">
        <Image
          src="/Seesunlogo_240x240.png"
          width={60}
          height={60}
          alt="시선 로고"
        />
        <h1 className="text-title-medium text-[#FFDB65]">See:Sun</h1>
      </div>

      <div className="flex-1 flex flex-col justify-center">
        <div className="grid-cols-2 grid gap-4">
          <Card title="식단" icon="fork_spoon" />
          <Card title="운동" icon="exercise" />
          <Card title="약" icon="pill" />
          <Card title="내정보" icon="person" />
        </div>
      </div>
    </div>
  );
}

// TODO: 나중에 폴더 구조 정해지면 분리하기
type CardProps = {
  title: string;
  icon: string;
};

function Card({ title, icon }: CardProps) {
  return (
    <div className="flex flex-col items-center gap-3 px-10 w-full cursor-pointer rounded-[16px] shadow-100 outline-2 outline-black bg-yellow-500 h-52.5 justify-center">
      <h2 className="break-keep text-title-small text-blue-900">{title}</h2>
      <Icon
        name={icon}
        size={64}
        filled
        className="text-blue-900"
        variation="rounded"
      />
    </div>
  );
}
