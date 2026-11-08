/* Copyright (c) 2007-2008 CSIRO
   Copyright (c) 2007-2011 Xiph.Org Foundation
   Originally written by Jean-Marc Valin, Gregory Maxwell, Koen Vos,
   Timothy B. Terriberry, and the Opus open-source contributors
   Ported to Java by Logan Stromberg

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

   - Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

   - Neither the name of Internet Society, IETF or IETF Trust, nor the
   names of specific contributors, may be used to endorse or promote
   products derived from this software without specific prior written
   permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
   OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.concentus;

/**
 * The Opus decoder structure.
 * <p>
 * Opus is a stateful codec with overlapping blocks and as a result Opus
 * packets are not coded independently of each other. Packets must be
 * passed into the decoder serially and in the correct order for a correct
 * decode. There is no packet loss concealment: every packet must be present.
 * <p>
 * A single codec state may only be accessed from a single thread at
 * a time and any required locking must be performed by the caller. Separate
 * streams must be decoded with separate decoder states and can be decoded
 * in parallel.
 */
public class OpusDecoder {

    int channels;
    int Fs;
    int decode_gain;

    /* Everything beyond this point gets cleared on a reset */
    int stream_channels;
    OpusBandwidth bandwidth;
    int frame_size;
    int last_packet_duration;
    int rangeFinal;
    final CeltDecoder Celt_Decoder = new CeltDecoder();

    OpusDecoder() {
    } // used internally

    void reset() {
        channels = 0;
        Fs = 0;
        decode_gain = 0;
        partialReset();
    }

    /// <summary>
    /// OPUS_DECODER_RESET_START
    /// </summary>
    void partialReset() {
        stream_channels = 0;
        bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
        frame_size = 0;
        last_packet_duration = 0;
        rangeFinal = 0;
    }

    int opus_decoder_init(int Fs, int channels) {
        CeltDecoder celt_dec;
        int ret;

        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000)
                || (channels != 1 && channels != 2)) {
            return OpusError.OPUS_BAD_ARG;
        }
        reset();

        celt_dec = Celt_Decoder;
        this.stream_channels = this.channels = channels;

        this.Fs = Fs;

        /* Initialize CELT decoder */
        ret = celt_dec.celt_decoder_init(Fs, channels);
        if (ret != OpusError.OPUS_OK) {
            return OpusError.OPUS_INTERNAL_ERROR;
        }

        this.frame_size = Fs / 400;
        return OpusError.OPUS_OK;
    }

    /**
     * Allocates and initializes a decoder state. Internally Opus stores data at
     * 48000 Hz, so that should be the default value for Fs. However, the
     * decoder can efficiently decode to buffers at 8, 12, 16, and 24 kHz so if
     * for some reason the caller cannot use data at the full sample rate, or
     * knows the compressed data doesn't use the full frequency range, it can
     * request decoding at a reduced rate. Likewise, the decoder is capable of
     * filling in either mono or interleaved stereo pcm buffers, at the caller's
     * request.
     *
     * @param Fs       Sample rate to decode at (Hz). This must be one of 8000, 12000,
     *                 16000, 24000, or 48000.
     * @param channels Number of channels (1 or 2) to decode.
     */
    public OpusDecoder(int Fs, int channels) throws OpusException {
        int ret;
        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000)) {
            throw new IllegalArgumentException("Sample rate is invalid (must be 8/12/16/24/48 Khz)");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Number of channels must be 1 or 2");
        }

        ret = opus_decoder_init(Fs, channels);
        if (ret != OpusError.OPUS_OK) {
            if (ret == OpusError.OPUS_BAD_ARG) {
                throw new IllegalArgumentException("OPUS_BAD_ARG when creating decoder");
            }
            throw new OpusException("Error while initializing decoder", ret);
        }
    }

    /**
     * A CELT frame with the silence flag set
     */
    private static final byte[] SILENCE = {(byte) 0xFF, (byte) 0xFF};

    int opus_decode_frame(byte[] data, int data_ptr,
                          int len, short[] pcm, int pcm_ptr, int frame_size) {
        CeltDecoder celt_dec;
        int i, celt_ret = 0;
        EntropyCoder dec = new EntropyCoder(); // porting note: stack var
        int F20;

        celt_dec = Celt_Decoder;
        F20 = Fs / 50;
        if (frame_size < F20 >> 3) {
            return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        /* A payload of 0 or 1 bytes (1 or 2 including ToC) is an empty frame (DTX):
           decode it as CELT digital silence. This fades out the previous frame's
           overlap and keeps the decoder state consistent. */
        boolean empty_frame = len <= 1;
        if (empty_frame) {
            data = SILENCE;
            data_ptr = 0;
            len = SILENCE.length;
        }
        dec.dec_init(data, data_ptr, len);

        if (this.frame_size > frame_size) {
            return OpusError.OPUS_BAD_ARG;
        }
        frame_size = this.frame_size;

        {
            int endband = 21;

            switch (bandwidth) {
                case OPUS_BANDWIDTH_NARROWBAND:
                    endband = 13;
                    break;
                case OPUS_BANDWIDTH_MEDIUMBAND:
                case OPUS_BANDWIDTH_WIDEBAND:
                    endband = 17;
                    break;
                case OPUS_BANDWIDTH_SUPERWIDEBAND:
                    endband = 19;
                    break;
                case OPUS_BANDWIDTH_FULLBAND:
                    endband = 21;
                    break;
            }
            celt_dec.SetEndBand(endband);
            celt_dec.SetChannels(stream_channels);
        }

        /* Decode CELT */
        celt_ret = celt_dec.celt_decode_with_ec(data, data_ptr,
                len, pcm, pcm_ptr, Inlines.IMIN(F20, frame_size), dec);

        if (decode_gain != 0) {
            int gain;
            gain = Inlines.celt_exp2(Inlines.MULT16_16_P15(Inlines.QCONST16(6.48814081e-4f, 25), decode_gain));
            for (i = pcm_ptr; i < pcm_ptr + (frame_size * channels); i++) {
                int x;
                x = Inlines.MULT16_32_P16(pcm[i], gain);
                pcm[i] = (short) Inlines.SATURATE(x, 32767);
            }
        }

        this.rangeFinal = empty_frame ? 0 : (int) dec.rng;

        return celt_ret < 0 ? celt_ret : frame_size;
    }

    int opus_decode_native(byte[] data, int data_ptr,
                           int len, short[] pcm_out, int pcm_out_ptr, int frame_size,
                           int self_delimited, BoxedValueInt packet_offset) {
        int i, nb_samples;
        int count, offset;
        int packet_frame_size, packet_stream_channels;
        packet_offset.Val = 0;
        OpusBandwidth packet_bandwidth;
        /* 48 x 2.5 ms = 120 ms */
        // fixme: make sure these values can fit in an int16
        short[] size = new short[48];
        /* There is no packet loss concealment: every call must have a packet */
        if (data == null || len <= 0) {
            return OpusError.OPUS_BAD_ARG;
        }

        packet_bandwidth = OpusPacketInfo.getBandwidth(data, data_ptr);
        packet_frame_size = OpusPacketInfo.getNumSamplesPerFrame(data, data_ptr, Fs);
        packet_stream_channels = OpusPacketInfo.getNumEncodedChannels(data, data_ptr);

        BoxedValueByte boxed_toc = new BoxedValueByte((byte) 0);
        BoxedValueInt boxed_offset = new BoxedValueInt(0);
        count = OpusPacketInfo.opus_packet_parse_impl(data, data_ptr, len, self_delimited, boxed_toc, null, 0,
                size, 0, boxed_offset, packet_offset);
        offset = boxed_offset.Val;

        if (count < 0) {
            return count;
        }

        data_ptr += offset;

        /* SILK and hybrid packets are not supported */
        if (OpusPacketInfo.getEncoderMode(data, data_ptr - offset) != OpusMode.MODE_CELT_ONLY) {
            return OpusError.OPUS_INVALID_PACKET;
        }

        if (count * packet_frame_size > frame_size) {
            return OpusError.OPUS_BUFFER_TOO_SMALL;
        }

        /* Update the state as the last step to avoid updating it on an invalid packet */
        this.bandwidth = packet_bandwidth;
        this.frame_size = packet_frame_size;
        this.stream_channels = packet_stream_channels;

        nb_samples = 0;
        for (i = 0; i < count; i++) {
            int ret;
            ret = opus_decode_frame(data, data_ptr, size[i], pcm_out, pcm_out_ptr + (nb_samples * channels), frame_size - nb_samples);
            if (ret < 0) {
                return ret;
            }
            Inlines.OpusAssert(ret == packet_frame_size);
            data_ptr += size[i];
            nb_samples += ret;
        }
        this.last_packet_duration = nb_samples;

        return nb_samples;
    }

    /**
     * Decodes an Opus packet. Only CELT packets are supported, and there is no packet loss
     * concealment: every call needs a packet. Empty frames (a payload of 0 or 1 bytes) decode as silence.
     *
     * @param in_data        The input payload. Must not be null.
     * @param in_data_offset The offset to use when reading the input payload. Usually 0
     * @param len            The number of bytes in the payload (the packet size). Must be greater than 0.
     * @param out_pcm        A buffer to put the output PCM, in a short array. The output size is (# of samples) * (# of channels).
     *                       You can use the OpusPacketInfo helpers to get a hint of the frame size before you decode the packet if you need exact sizing.
     * @param out_pcm_offset The offset to use when writing to the output buffer
     * @param frame_size     The number of samples (per channel) of available space in the output PCM buf.
     *                       If this is less than the maximum packet duration (120ms; 5760 for 48khz), this function will
     *                       not be capable of decoding some packets.
     * @return The number of decoded samples (per channel)
     */
    public int decode(byte[] in_data, int in_data_offset,
                      int len, short[] out_pcm, int out_pcm_offset, int frame_size) throws OpusException {
        if (in_data == null) {
            throw new IllegalArgumentException("Packet data must not be null (packet loss concealment is not supported)");
        }
        if (len <= 0) {
            throw new IllegalArgumentException("Packet length must be > 0");
        }
        if (frame_size <= 0) {
            throw new IllegalArgumentException("Frame size must be > 0");
        }

        try {
            BoxedValueInt dummy = new BoxedValueInt(0);
            int ret = opus_decode_native(in_data, in_data_offset, len, out_pcm, out_pcm_offset, frame_size, 0, dummy);

            if (ret < 0) {
                // An error happened; report it
                if (ret == OpusError.OPUS_BAD_ARG) {
                    throw new IllegalArgumentException("OPUS_BAD_ARG while decoding");
                }
                throw new OpusException("An error occurred during decoding", ret);
            }

            return ret;
        } catch (ArithmeticException e) {
            throw new OpusException("Internal error during decoding: " + e.getMessage());
        }
    }

    /**
     * Decodes an Opus packet into 16-bit little-endian PCM bytes. See
     * {@link #decode(byte[], int, int, short[], int, int)} for the details.
     *
     * @param in_data        The input payload. Must not be null.
     * @param in_data_offset The offset to use when reading the input payload. Usually 0
     * @param len            The number of bytes in the payload (the packet size). Must be greater than 0.
     * @param out_pcm        A buffer to put the output PCM, in a byte array. The output size is (# of samples) * (# of channels) * 2.
     * @param out_pcm_offset The offset to use when writing to the output buffer
     * @param frame_size     The number of samples (per channel) of available space in the output PCM buf.
     * @return The number of decoded samples (per channel)
     */
    public int decode(byte[] in_data, int in_data_offset, int len, byte[] out_pcm,
                      int out_pcm_offset, int frame_size) throws OpusException {
        short[] spcm = new short[Math.min(frame_size, 5760) * channels];
        int decSamples = decode(in_data, in_data_offset, len, spcm, 0, frame_size);
        //Convert short array to byte array
        for (int c = 0, idx = out_pcm_offset; c < spcm.length; c++) {
            out_pcm[idx++] = (byte) (spcm[c] & 0xff);
            out_pcm[idx++] = (byte) ((spcm[c] >> 8) & 0xff);
        }
        return decSamples;
    }

    public OpusBandwidth getBandwidth() {
        return bandwidth;
    }

    public int getFinalRange() {
        return rangeFinal;
    }

    public int getSampleRate() {
        return Fs;
    }

    public int getPitch() {
        return Celt_Decoder.GetPitch();
    }

    public int getGain() {
        return decode_gain;
    }

    public void setGain(int value) {
        if (value < -32768 || value > 32767) {
            throw new IllegalArgumentException("Gain must be within the range of a signed int16");
        }

        decode_gain = value;
    }

    public int getLastPacketDuration() {
        return last_packet_duration;
    }

    public void resetState() {
        partialReset();
        Celt_Decoder.ResetState();
        stream_channels = channels;
        frame_size = Fs / 400;
    }
}
