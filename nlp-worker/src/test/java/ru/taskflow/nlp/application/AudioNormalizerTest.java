package ru.taskflow.nlp.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.shared.resources.*;
import javax.sound.sampled.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class AudioNormalizerTest {
    byte[] wav(int seconds) throws IOException {
        byte[] pcm=new byte[seconds*32000];var out=new ByteArrayOutputStream();
        try(var in=new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat(16000,16,1,true,false),pcm.length/2)){
            AudioSystem.write(in,AudioFileFormat.Type.WAVE,out);
        }return out.toByteArray();
    }
    @Test void durationUsesDecodedSamplesAndProducesBoundedWav() throws IOException {
        var policy=new ResourcePolicy();policy.setMaxAudioSeconds(2);
        var audio=new AudioNormalizer(policy).normalize(wav(2));
        assertThat(audio.seconds()).isEqualTo(2);assertThat(audio.wav()).hasSize(64044);
        assertThatThrownBy(()->new AudioNormalizer(policy).normalize(wav(3))).isInstanceOf(ResourceRejectedException.class);
    }
    @Test void emptyInputIsRejectedWithoutPaidWork(){
        assertThatThrownBy(()->new AudioNormalizer(new ResourcePolicy()).normalize(new byte[0])).isInstanceOf(ResourceRejectedException.class);
    }
}
