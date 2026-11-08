package ru.taskflow.nlp.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import ru.taskflow.nlp.domain.SpeechToTextProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    @Mock
    private SpeechToTextProvider speechToTextProvider;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TranscriptionService service;
    private ru.taskflow.shared.resources.ModelRequestContext.Scope scope;
    @org.junit.jupiter.api.AfterEach void closeContext(){scope.close();}

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        var normalizer=org.mockito.Mockito.mock(AudioNormalizer.class);
        org.mockito.Mockito.lenient().when(normalizer.normalize(any())).thenAnswer(i->new AudioNormalizer.Audio(i.getArgument(0),1));
        service = new TranscriptionService(speechToTextProvider, redis,normalizer,new ru.taskflow.shared.resources.ResourcePolicy());
        scope=ru.taskflow.shared.resources.ModelRequestContext.open(new ru.taskflow.shared.resources.ModelRequestContext(
                java.util.UUID.randomUUID(),System.currentTimeMillis()+35000,0));
    }

    @Test
    void transcribe_returnsText() {
        byte[] audio = {1, 2, 3};
        when(valueOps.get(anyString())).thenReturn(null);
        when(speechToTextProvider.transcribeAudio(audio)).thenReturn("закрой молоко");

        String text = service.transcribe(audio);

        assertThat(text).isEqualTo("закрой молоко");
        verify(valueOps).set(anyString(), eq("закрой молоко"), eq(Duration.ofDays(1)));
    }

    @Test
    void transcribe_usesCacheOnSecondCall() {
        byte[] audio = {1, 2, 3};
        when(valueOps.get(anyString())).thenReturn(null);
        when(speechToTextProvider.transcribeAudio(audio)).thenReturn("закрой молоко");

        service.transcribe(audio);

        when(valueOps.get(anyString())).thenReturn("закрой молоко");
        String second = service.transcribe(audio);

        assertThat(second).isEqualTo("закрой молоко");
        verify(speechToTextProvider, times(1)).transcribeAudio(audio);
    }

    @Test
    void transcribe_differentAudioMissesCache() {
        byte[] audio1 = {1, 2, 3};
        byte[] audio2 = {4, 5, 6};
        when(valueOps.get(anyString())).thenReturn(null);
        when(speechToTextProvider.transcribeAudio(audio1)).thenReturn("текст один");
        when(speechToTextProvider.transcribeAudio(audio2)).thenReturn("текст два");

        String first = service.transcribe(audio1);
        String secondDifferent = service.transcribe(audio2);

        assertThat(first).isEqualTo("текст один");
        assertThat(secondDifferent).isEqualTo("текст два");
        verify(speechToTextProvider).transcribeAudio(audio1);
        verify(speechToTextProvider).transcribeAudio(audio2);

        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).get(keys.capture());
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    @Test void failedTranscriptionIsNotCached() {
        when(speechToTextProvider.transcribeAudio(any())).thenReturn("");
        assertThat(service.transcribe(new byte[]{1,2,3})).isEmpty();
        org.mockito.Mockito.verify(valueOps,org.mockito.Mockito.never()).set(anyString(),anyString(),any(Duration.class));
    }
}
