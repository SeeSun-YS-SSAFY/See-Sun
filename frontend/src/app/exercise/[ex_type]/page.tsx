

export default async function ExerciseType({
    params,
}: {
    params : Promise<{ ex_type : string}> 
}) {
    const { ex_type } = await params
    return (
        <div>
            { ex_type } 종류
        </div>
    )
}