package kr.seesun.core.voice

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kr.seesun.core.voice.lifecycle.ActivityAppVisibility
import kr.seesun.core.voice.lifecycle.AppVisibility
import kr.seesun.core.voice.parse.FoodMatcher
import kr.seesun.core.voice.parse.MealUtteranceParser
import kr.seesun.core.voice.recognition.AndroidSpeechInput
import kr.seesun.core.voice.recognition.OnsetTuningSource
import kr.seesun.core.voice.recognition.SpeechInput
import kr.seesun.core.voice.recognition.SpeechOnsetDetector
import kr.seesun.core.voice.recognition.VadOnsetDetector
import kr.seesun.core.voice.settings.SharedVoicePreferences
import kr.seesun.core.voice.settings.VoicePreferences
import kr.seesun.core.voice.tts.AndroidGuidanceSpeaker
import kr.seesun.core.voice.tts.Speaker

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VoiceBindings {
    @Binds
    abstract fun bindSpeechInput(impl: AndroidSpeechInput): SpeechInput

    @Binds
    abstract fun bindSpeaker(impl: AndroidGuidanceSpeaker): Speaker

    @Binds
    abstract fun bindAppVisibility(impl: ActivityAppVisibility): AppVisibility

    @Binds
    abstract fun bindVoicePreferences(impl: SharedVoicePreferences): VoicePreferences

    @Binds
    abstract fun bindOnsetDetector(impl: VadOnsetDetector): SpeechOnsetDetector

    /** v7 말 시작 판정 값(feature:admin이 넣는다, 없으면 기본값). */
    @BindsOptionalOf
    abstract fun onsetTuningSource(): OnsetTuningSource
}

@Module
@InstallIn(SingletonComponent::class)
internal object VoiceProviders {
    @Provides
    @Singleton
    fun foodMatcher(): FoodMatcher = FoodMatcher()

    @Provides
    fun mealUtteranceParser(matcher: FoodMatcher): MealUtteranceParser = MealUtteranceParser(matcher)
}
