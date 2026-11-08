package org.concentus.console;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.concentus.OpusMSDecoder;
import org.gagravarr.opus.OpusAudioData;
import org.gagravarr.opus.OpusFile;
import org.gagravarr.opus.OpusInfo;

import java.io.*;
import java.nio.file.Files;

/**
 * Decodes an Ogg Opus file to a 16-bit PCM WAV file.
 * <p>
 * Usage: {@code Program <input.opus> <output.wav> [sample rate]}
 * <p>
 * The sample rate defaults to 48000 and must be one of 8000, 12000, 16000, 24000 or 48000.
 * Pre-skip and end trimming from the Ogg granule positions are applied, as is the header's output gain.
 */
public class Program {

    /* Opus granule positions are always in 48 kHz samples */
    private static final int GRANULE_RATE = 48000;
    /* 120 ms at 48 kHz, the longest possible Opus packet */
    private static final int MAX_PACKET_SAMPLES = 5760;

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: Program <input.opus> <output.wav> [sample rate]");
            System.exit(2);
        }
        int rate = args.length == 3 ? Integer.parseInt(args[2]) : GRANULE_RATE;

        try {
            long start = System.nanoTime();
            long samples = decode(new File(args[0]), new File(args[1]), rate);
            double seconds = (System.nanoTime() - start) / 1e9;
            System.out.printf("Decoded %.1f s of audio in %.2f s (%.0fx realtime)%n",
                    samples / (double) rate, seconds, samples / (double) rate / seconds);
        } catch (IOException | OpusException | IllegalArgumentException e) {
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Decodes {@code in} to a WAV file at {@code rate} Hz.
     *
     * @return the number of samples (per channel) written
     */
    public static long decode(File in, File out, int rate) throws IOException, OpusException {
        try (OpusFile opus = new OpusFile(in)) {
            OpusInfo info = opus.getInfo();
            int channels = info.getNumChannels();
            int family = info.getChannelMappingFamily() & 0xFF;

            /* Single-stream files (mapping family 0) use a plain decoder, everything else the multistream one */
            OpusDecoder decoder = null;
            OpusMSDecoder msDecoder = null;
            if (family == 0) {
                decoder = new OpusDecoder(rate, channels);
                decoder.setGain(info.getOutputGain());
            } else {
                byte[] map = info.getChannelMapping();
                short[] mapping = new short[channels];
                for (int i = 0; i < channels; i++) {
                    mapping[i] = (short) (map[i] & 0xFF);
                }
                msDecoder = OpusMSDecoder.create(rate, channels, info.getStreamCount() & 0xFF,
                        info.getTwoChannelStreamCount() & 0xFF, mapping);
                msDecoder.setGain(info.getOutputGain());
            }

            int maxSamples = MAX_PACKET_SAMPLES * rate / GRANULE_RATE;
            short[] pcm = new short[maxSamples * channels];
            byte[] bytes = new byte[maxSamples * channels * 2];

            /* Pre-skip is given in 48 kHz samples; drop that many (scaled) samples from the start */
            long toSkip = (long) info.getPreSkip() * rate / GRANULE_RATE;
            long written = 0;

            try (WavWriter wav = new WavWriter(out, rate, channels)) {
                OpusAudioData packet;
                while ((packet = opus.getNextAudioPacket()) != null) {
                    byte[] data = packet.getData();
                    int n;
                    if (decoder != null) {
                        n = decoder.decode(data, 0, data.length, pcm, 0, maxSamples);
                    } else {
                        n = msDecoder.decodeMultistream(data, 0, data.length, pcm, 0, maxSamples);
                        if (n < 0) {
                            throw new OpusException("Multistream decode failed", n);
                        }
                    }

                    int first = (int) Math.min(toSkip, n);
                    toSkip -= first;
                    int count = n - first;

                    /* The granule position of the last packet on a page marks where the audio ends;
                       on the final page this trims the encoder's padding */
                    long granule = packet.getGranulePosition();
                    if (granule >= 0) {
                        long end = Math.max(0, (granule - info.getPreSkip()) * rate / GRANULE_RATE);
                        count = (int) Math.max(0, Math.min(count, end - written));
                    }

                    for (int i = 0; i < count * channels; i++) {
                        short s = pcm[first * channels + i];
                        bytes[2 * i] = (byte) s;
                        bytes[2 * i + 1] = (byte) (s >> 8);
                    }
                    wav.write(bytes, count * channels * 2);
                    written += count;
                }
            }
            return written;
        }
    }

    /**
     * Minimal streaming writer for 16-bit PCM WAV files. The header sizes are filled in on close.
     */
    private static final class WavWriter implements AutoCloseable {

        private final File file;
        private final OutputStream out;
        private long dataBytes = 0;

        WavWriter(File file, int rate, int channels) throws IOException {
            this.file = file;
            this.out = new BufferedOutputStream(Files.newOutputStream(file.toPath()), 1 << 16);
            byte[] h = new byte[44];
            putAscii(h, 0, "RIFF");
            putAscii(h, 8, "WAVE");
            putAscii(h, 12, "fmt ");
            putInt(h, 16, 16);                    // fmt chunk size
            putShort(h, 20, 1);                   // PCM
            putShort(h, 22, channels);
            putInt(h, 24, rate);
            putInt(h, 28, rate * channels * 2);   // byte rate
            putShort(h, 32, channels * 2);        // block align
            putShort(h, 34, 16);                  // bits per sample
            putAscii(h, 36, "data");
            out.write(h);
        }

        void write(byte[] b, int len) throws IOException {
            out.write(b, 0, len);
            dataBytes += len;
        }

        @Override
        public void close() throws IOException {
            out.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                byte[] b = new byte[4];
                putInt(b, 0, (int) (36 + dataBytes));
                raf.seek(4);
                raf.write(b);
                putInt(b, 0, (int) dataBytes);
                raf.seek(40);
                raf.write(b);
            }
        }

        private static void putAscii(byte[] b, int off, String s) {
            for (int i = 0; i < 4; i++) {
                b[off + i] = (byte) s.charAt(i);
            }
        }

        private static void putShort(byte[] b, int off, int v) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >> 8);
        }

        private static void putInt(byte[] b, int off, int v) {
            putShort(b, off, v);
            putShort(b, off + 2, v >> 16);
        }
    }
}
