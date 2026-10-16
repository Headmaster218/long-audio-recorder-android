package io.github.headmaster218.recorder.android;

/** Only guards acquisition/idle confirmation; never held during microphone capture. */
final class SpoolAdmission { static final Object LOCK = new Object(); private SpoolAdmission() { } }
