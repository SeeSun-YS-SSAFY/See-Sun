package kr.seesun.core.voice

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kr.seesun.core.voice.parse.FoodMatcher
import kr.seesun.core.voice.parse.MealUtteranceParser
import kr.seesun.core.voice.recognition.AndroidSpeechInput
import kr.seesun.core.voice.recognition.SpeechInput
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
    abstract fun bindVoicePreferences(impl: SharedVoicePreferences): VoicePreferences
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
