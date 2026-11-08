package ru.taskflow.nlp.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.taskflow.shared.resources.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.*;
import java.util.concurrent.*;

@Component
public class AudioNormalizer {
    private final ResourcePolicy policy;
    public AudioNormalizer(ResourcePolicy policy){this.policy=policy;}
    public record Audio(byte[] wav,int seconds) { }
    public Audio normalize(byte[] input) {
        if(input.length==0 || input.length>16_000_000) throw invalid("Недопустимый размер аудио");
        int maxBytes=Math.multiplyExact(policy.getMaxAudioSeconds(),32_000);
        byte[] pcm=null;
        try(var stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(input))) {
            var f=stream.getFormat();
            if(f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) && f.getSampleRate()==16_000
                    && f.getSampleSizeInBits()==16 && f.getChannels()==1 && !f.isBigEndian())
                pcm=stream.readNBytes(maxBytes+2);
        } catch(UnsupportedAudioFileException|IOException ignored) { }
        if(pcm==null) pcm=decode(input,maxBytes);
        if(pcm.length<320 || pcm.length%2!=0 || pcm.length>maxBytes)
            throw invalid("Аудио пустое или длиннее допустимого предела");
        var wav=ByteBuffer.allocate(44+pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36+pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '})
                .putInt(16).putShort((short)1).putShort((short)1).putInt(16_000).putInt(32_000)
                .putShort((short)2).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return new Audio(wav.array(),(pcm.length+31_999)/32_000);
    }
    private byte[] decode(byte[] input,int maxBytes) {
        Process process;
        try {
            process=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-threads","1",
                    "-protocol_whitelist","cache,pipe","-read_ahead_limit","16000000","-i","cache:pipe:0","-map","0:a:0","-vn","-sn","-dn",
                    "-ac","1","-ar","16000","-t",""+(policy.getMaxAudioSeconds()+1),"-f","s16le","pipe:1")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch(IOException unavailable) { throw ResourceRejectedException.unavailable(); }
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                executor.submit(()-> {try(var output=process.getOutputStream()){output.write(input);}catch(IOException ignored){} });
                var result=executor.submit(()->process.getInputStream().readNBytes(maxBytes+1));
                long timeout=Math.min(5_000,ModelRequestContext.required().remainingMillis());
                long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
                byte[] bytes=result.get(timeout,TimeUnit.MILLISECONDS);
                if(bytes.length>maxBytes) throw invalid("Аудио длиннее допустимого предела");
                long left=Math.max(1,TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime()));
                if(!process.waitFor(left,TimeUnit.MILLISECONDS) || process.exitValue()!=0)
                    throw invalid("Не удалось прочитать аудио");
                return bytes;
            } catch(TimeoutException|ExecutionException exception) {throw invalid("Не удалось прочитать аудио за отведённое время");
            } catch(InterruptedException exception) {Thread.currentThread().interrupt();throw ResourceRejectedException.unavailable();
            } finally {
                process.destroyForcibly();
                try {process.getInputStream().close();process.getOutputStream().close();}catch(IOException ignored){}
            }
        }
    }
    private ResourceRejectedException invalid(String text) {return new ResourceRejectedException(HttpStatus.PAYLOAD_TOO_LARGE,text);}
}
