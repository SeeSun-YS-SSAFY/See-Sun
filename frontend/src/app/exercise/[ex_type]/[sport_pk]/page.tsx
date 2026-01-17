


export default async function ExerciseType({
    params,
}: {
    params : Promise<{ sport_pk : string}>
}) {
    const { sport_pk } = await params
    return (
        <div>
            { sport_pk } 운동
            여기가 존나 빡셀듯
        </div>
    )
}