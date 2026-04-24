package ru.taskflow.nlp.domain;

public interface SpeechToTextProvider {
    String transcribeAudio(byte[] audioBytes);
}
