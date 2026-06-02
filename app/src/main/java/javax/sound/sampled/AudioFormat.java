package javax.sound.sampled;

import java.util.HashMap;
import java.util.Map;

public class AudioFormat {

    public static class Encoding {
        private final String name;
        public Encoding(String name) { this.name = name; }
        @Override public String toString() { return name; }
        @Override public boolean equals(Object o) {
            return o instanceof Encoding && name.equals(((Encoding) o).name);
        }
        @Override public int hashCode() { return name == null ? 0 : name.hashCode(); }
    }

    private final Encoding encoding;
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final int frameSize;
    private final float frameRate;
    private final boolean bigEndian;

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels,
                       boolean signed, boolean bigEndian) {
        this(new Encoding(signed ? "PCM_SIGNED" : "PCM_UNSIGNED"), sampleRate,
                sampleSizeInBits, channels,
                (channels == -1 || sampleSizeInBits == -1)
                        ? -1 : ((sampleSizeInBits + 7) / 8) * channels,
                sampleRate, bigEndian);
    }

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits,
                       int channels, int frameSize, float frameRate, boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }
    public Map<String, Object> properties() { return new HashMap<String, Object>(); }
}
