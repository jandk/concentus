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

import java.util.Arrays;

/**
 * The Opus decoder structure.
 * <p>
 * Opus is a stateful codec with overlapping blocks and as a result Opus
 * packets are not coded independently of each other. Packets must be
 * passed into the decoder serially and in the correct order for a correct
 * decode. Lost packets can be replaced with loss concealment by calling
 * the decoder with a null reference and zero length for the missing packet.
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
    boolean got_packet;
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
        got_packet = false;
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

        celt_dec.SetSignalling(0);

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

    int opus_decode_frame(byte[] data, int data_ptr,
                          int len, short[] pcm, int pcm_ptr, int frame_size, int decode_fec) {
        CeltDecoder celt_dec;
        int i, celt_ret = 0;
        EntropyCoder dec = new EntropyCoder(); // porting note: stack var

        int audiosize;
        int F2_5, F5, F10, F20;

        celt_dec = Celt_Decoder;
        F20 = Fs / 50;
        F10 = F20 >> 1;
        F5 = F10 >> 1;
        F2_5 = F5 >> 1;
        if (frame_size < F2_5) {
            return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        /* Limit frame_size to avoid excessive stack allocations. */
        frame_size = Inlines.IMIN(frame_size, Fs / 25 * 3);
        /* Payloads of 1 (2 including ToC) or 0 trigger the PLC/DTX */
        if (len <= 1) {
            data = null;
            /* In that case, don't conceal more than what the ToC says */
            frame_size = Inlines.IMIN(frame_size, this.frame_size);
        }
        if (data != null) {
            audiosize = this.frame_size;
            dec.dec_init(data, data_ptr, len);
        } else {
            audiosize = frame_size;

            if (!got_packet) {
                /* If we haven't got any packet yet, all we can do is return zeros */
                Arrays.fill(pcm, pcm_ptr, pcm_ptr + (audiosize * channels), (short) 0);
                return audiosize;
            }

            /* Avoids trying to run the PLC on sizes other than 2.5 (CELT), 5 (CELT),
               10, or 20 (e.g. 12.5 or 30 ms). */
            if (audiosize > F20) {
                do {
                    int ret = opus_decode_frame(null, 0, 0, pcm, pcm_ptr, Inlines.IMIN(audiosize, F20), 0);
                    if (ret < 0) {

                        return ret;
                    }
                    pcm_ptr += ret * channels;
                    audiosize -= ret;
                } while (audiosize > 0);

                return frame_size;
            } else if (audiosize < F20) {
                if (audiosize > F10) {
                    audiosize = F10;
                } else if (audiosize > F5 && audiosize < F10) {
                    audiosize = F5;
                }
            }
        }

        if (audiosize > frame_size) {
            return OpusError.OPUS_BAD_ARG;
        } else {
            frame_size = audiosize;
        }

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

        celt_dec.SetStartBand(0);

        /* Decode CELT */
        celt_ret = celt_dec.celt_decode_with_ec(decode_fec != 0 ? null : data, data_ptr,
                len, pcm, pcm_ptr, Inlines.IMIN(F20, frame_size), dec, 0);

        if (decode_gain != 0) {
            int gain;
            gain = Inlines.celt_exp2(Inlines.MULT16_16_P15(((short) (0.5 + (6.48814081e-4f) * ((1) << (25))))/*Inlines.QCONST16(6.48814081e-4f, 25)*/, decode_gain));
            for (i = pcm_ptr; i < pcm_ptr + (frame_size * channels); i++) {
                int x;
                x = Inlines.MULT16_32_P16(pcm[i], gain);
                pcm[i] = (short) Inlines.SATURATE(x, 32767);
            }
        }

        if (len <= 1) {
            this.rangeFinal = 0;
        } else {
            this.rangeFinal = (int) dec.rng;
        }

        if (data != null) {
            this.got_packet = true;
        }

        return celt_ret < 0 ? celt_ret : audiosize;
    }

    int opus_decode_native(byte[] data, int data_ptr,
                           int len, short[] pcm_out, int pcm_out_ptr, int frame_size, int decode_fec,
                           int self_delimited, BoxedValueInt packet_offset) {
        int i, nb_samples;
        int count, offset;
        int packet_frame_size, packet_stream_channels;
        packet_offset.Val = 0;
        OpusBandwidth packet_bandwidth;
        /* 48 x 2.5 ms = 120 ms */
        // fixme: make sure these values can fit in an int16
        short[] size = new short[48];
        if (decode_fec < 0 || decode_fec > 1) {
            return OpusError.OPUS_BAD_ARG;
        }
        /* For FEC/PLC, frame_size has to be to have a multiple of 2.5 ms */
        if ((decode_fec != 0 || len == 0 || data == null) && frame_size % (Fs / 400) != 0) {
            return OpusError.OPUS_BAD_ARG;
        }
        if (len == 0 || data == null) {
            int pcm_count = 0;
            do {
                int ret;
                ret = opus_decode_frame(null, 0, 0, pcm_out, pcm_out_ptr + (pcm_count * channels), frame_size - pcm_count, 0);
                if (ret < 0) {
                    return ret;
                }
                pcm_count += ret;
            } while (pcm_count < frame_size);
            Inlines.OpusAssert(pcm_count == frame_size);
            this.last_packet_duration = pcm_count;
            return pcm_count;
        } else if (len < 0) {
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

        if (decode_fec != 0) {
            /* CELT has no FEC, so just run the PLC (recursive call) */
            return opus_decode_native(null, 0, 0, pcm_out, pcm_out_ptr, frame_size, 0, 0, new BoxedValueInt(0));
        }

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
            ret = opus_decode_frame(data, data_ptr, size[i], pcm_out, pcm_out_ptr + (nb_samples * channels), frame_size - nb_samples, 0);
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

    /// <summary>
    /// Decodes an Opus packet.
    /// </summary>
    /// <param name="in_data"></param>
    /// <param name="in_data_offset"></param>
    /// <param name="len"></param>
    /// <param name="out_pcm">
    ///
    /// exact sizing.</param>
    /// <param name="out_pcm_offset"></param>
    /// <param name="frame_size"></param>
    /// <param name="decode_fec">Flag to request that any in-band forward error correction data be
    /// decoded. If no such data is available, the frame is decoded as if it were lost.</param>
    /// <returns>The number of decoded samples</returns>
    /**
     * Decodes an Opus packet.
     *
     * @param in_data        The input payload. This may be NULL if that previous packet was lost in transit (when PLC is enabled)
     * @param in_data_offset The offset to use when reading the input payload. Usually 0
     * @param len            The number of bytes in the payload (the packet size)
     * @param out_pcm        A buffer to put the output PCM, in a short array. The output size is (# of samples) * (# of channels).
     *                       You can use the OpusPacketInfo helpers to get a hint of the frame size before you decode the packet if you need exact sizing.
     * @param out_pcm_offset The offset to use when writing to the output buffer
     * @param frame_size     The number of samples (per channel) of available space in the output PCM buf.
     *                       If this is less than the maximum packet duration (120ms; 5760 for 48khz), this function will
     *                       not be capable of decoding some packets. In the case of PLC (data == NULL) or FEC (decode_fec == true),
     *                       then frame_size needs to be exactly the duration of the audio that is missing, otherwise the decoder will
     *                       not be in an optimal state to decode the next incoming packet. For the PLC and FEC cases, frame_size *must*
     *                       be a multiple of 2.5 ms.
     * @param decode_fec     Indicates that we want to recreate the PREVIOUS (lost) packet using FEC data from THIS packet. Using this packet
     *                       recovery scheme, you will actually decode this packet twice, first with decode_fec TRUE and then again with FALSE. If FEC data is not
     *                       available in this packet, the decoder will simply generate a best-effort recreation of the lost packet. In that case,
     *                       the length of frame_size must be EXACTLY the length of the audio that was lost, or else the decoder will be in an inconsistent state.
     * @return The number of decoded samples (per channel)
     */
    public int decode(byte[] in_data, int in_data_offset,
                      int len, short[] out_pcm, int out_pcm_offset, int frame_size, boolean decode_fec) throws OpusException {
        if (frame_size <= 0) {
            throw new IllegalArgumentException("Frame size must be > 0");
        }

        try {
            BoxedValueInt dummy = new BoxedValueInt(0);
            int ret = opus_decode_native(in_data, in_data_offset, len, out_pcm, out_pcm_offset, frame_size, decode_fec ? 1 : 0, 0, dummy);

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
     * Decodes an Opus packet.
     *
     * @param in_data        The input payload. This may be NULL if that previous packet was lost in transit (when PLC is enabled)
     * @param in_data_offset The offset to use when reading the input payload. Usually 0
     * @param len            The number of bytes in the payload (the packet size)
     * @param out_pcm        A buffer to put the output PCM, in a byte array. The output size is (# of samples) * (# of channels) * 2.
     *                       You can use the OpusPacketInfo helpers to get a hint of the frame size before you decode the packet if you need exact sizing.
     * @param out_pcm_offset The offset to use when writing to the output buffer
     * @param frame_size     The number of samples (per channel) of available space in the output PCM buf.
     *                       If this is less than the maximum packet duration (120ms; 5760 for 48khz), this function will
     *                       not be capable of decoding some packets. In the case of PLC (data == NULL) or FEC (decode_fec == true),
     *                       then frame_size needs to be exactly the duration of the audio that is missing, otherwise the decoder will
     *                       not be in an optimal state to decode the next incoming packet. For the PLC and FEC cases, frame_size *must*
     *                       be a multiple of 2.5 ms.
     * @param decode_fec     Indicates that we want to recreate the PREVIOUS (lost) packet using FEC data from THIS packet. Using this packet
     *                       recovery scheme, you will actually decode this packet twice, first with decode_fec TRUE and then again with FALSE. If FEC data is not
     *                       available in this packet, the decoder will simply generate a best-effort recreation of the lost packet. In that case, the
     *                       length of frame_size must be EXACTLY the length of the audio that was lost, or else the decoder will be in an inconsistent state.
     * @return The number of decoded samples (per channel)
     */
    public int decode(byte[] in_data, int in_data_offset, int len, byte[] out_pcm,
                      int out_pcm_offset, int frame_size, boolean decode_fec) throws OpusException {
        short[] spcm = new short[Math.min(frame_size, 5760) * channels];
        int decSamples = decode(in_data, in_data_offset, len, spcm, 0, frame_size, decode_fec);
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
