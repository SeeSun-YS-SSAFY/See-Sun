package kr.seesun.feature.exercise.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 운동 안내 음성 다운로드 캐시(03 §7 "앱은 운동 음성을 다운로드 캐시"). 한 번 들은(또는 미리 받은) 구간은
 * 비행기 모드에서도 재생된다. 최대 [MAX_CACHE_BYTES]까지, 오래 안 쓴 것부터 지운다.
 */
@OptIn(UnstableApi::class)
@Singleton
class ExerciseAudioCache @Inject constructor(@ApplicationContext context: Context) {
    // SimpleCache는 폴더 하나에 프로세스당 한 개만 만들 수 있다(Hilt 컴포넌트가 다시 만들어져도 공유).
    private val cache = shared(context.applicationContext)

    val dataSourceFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(TIMEOUT_MS)
                .setReadTimeoutMs(TIMEOUT_MS)
                .setUserAgent("SeeSun-Android")
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * 운동을 시작할 때 이 운동(루틴이면 다음 운동까지)의 구간 음성을 미리 받아 둔다.
     * 중간에 비행기 모드가 되어도 끊기지 않게 하려는 것. 실패해도 재생 때 다시 시도하므로 조용히 넘긴다.
     */
    suspend fun prefetch(urls: List<String>) = withContext(Dispatchers.IO) {
        for (url in urls) {
            try {
                CacheWriter(dataSourceFactory.createDataSource(), DataSpec(url.toUri()), null, null).cache()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // 네트워크 없음 등: 재생할 때 다시 받는다.
            }
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 150L * 1024 * 1024
        const val TIMEOUT_MS = 10_000

        @Volatile
        private var instance: SimpleCache? = null

        fun shared(context: Context): SimpleCache = instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.cacheDir, "exercise-audio"),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { instance = it }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ExercisePlayerModule {
    @OptIn(UnstableApi::class)
    @Provides
    fun guidePlayerFactory(@ApplicationContext context: Context, cache: ExerciseAudioCache): GuidePlayerFactory = GuidePlayerFactory {
        Media3GuidePlayer(context, DefaultMediaSourceFactory(cache.dataSourceFactory))
    }
}
