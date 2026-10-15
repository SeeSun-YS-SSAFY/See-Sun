package kr.seesun.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kr.seesun.core.data.db.ExerciseLogDao
import kr.seesun.core.data.db.FavoriteExerciseDao
import kr.seesun.core.data.db.SeeSunDatabase
import kr.seesun.core.data.repository.DefaultExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseContentRepository
import kr.seesun.core.data.repository.ExerciseLogRepository
import kr.seesun.core.data.repository.FavoriteExerciseRepository
import kr.seesun.core.data.repository.OfflineFirstExerciseLogRepository
import kr.seesun.core.data.repository.RoomFavoriteExerciseRepository

/** 운동 데이터(콘텐츠·즐겨찾기·기록 큐). 운동 표는 앱 DB([SeeSunDatabase] v3)에 있다. */
@Module
@InstallIn(SingletonComponent::class)
object ExerciseDatabaseModule {
    @Provides
    fun exerciseLogDao(db: SeeSunDatabase): ExerciseLogDao = db.exerciseLogDao()

    @Provides
    fun favoriteExerciseDao(db: SeeSunDatabase): FavoriteExerciseDao = db.favoriteExerciseDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExerciseRepositoryModule {
    @Binds
    abstract fun content(impl: DefaultExerciseContentRepository): ExerciseContentRepository

    @Binds
    abstract fun favorites(impl: RoomFavoriteExerciseRepository): FavoriteExerciseRepository

    @Binds
    abstract fun logs(impl: OfflineFirstExerciseLogRepository): ExerciseLogRepository
}

/** 전송 예약. 기기 테스트는 이 모듈을 바꿔 끼운다(@TestInstallIn). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExerciseSyncModule {
    @Binds
    abstract fun scheduler(impl: kr.seesun.core.data.sync.WorkManagerExerciseSyncScheduler): kr.seesun.core.data.sync.ExerciseSyncScheduler
}
