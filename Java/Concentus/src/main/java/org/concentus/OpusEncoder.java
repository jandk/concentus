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

/// <summary>
/// The Opus encoder structure
/// </summary>
public class OpusEncoder {

    final EncControlState silk_mode = new EncControlState();
    int channels;
    int force_channels;
    OpusBandwidth user_bandwidth;
    OpusBandwidth max_bandwidth;
    int Fs;
    int use_vbr;
    int vbr_constraint;
    OpusFramesize variable_duration;
    int bitrate_bps;
    int user_bitrate_bps;
    int lsb_depth;
    int lfe;
    final TonalityAnalysisState analysis = new TonalityAnalysisState();

    // partial reset happens below this line
    int stream_channels;
    short hybrid_stereo_width_Q14;
    final int[] hp_mem = new int[4];
    int prev_framesize;
    OpusBandwidth bandwidth;
    int first;
    int[] energy_masking;
    OpusBandwidth detected_bandwidth;
    int rangeFinal;

    // [Porting Note] There were originally "cabooses" that were tacked onto the end
    // of the struct without being explicitly included (since they have a variable size).
    // Here they are just included as an intrinsic variable.
    final CeltEncoder Celt_Encoder = new CeltEncoder();

    OpusEncoder() {
    } // used internally

    void reset() {
        silk_mode.Reset();
        channels = 0;
        force_channels = 0;
        user_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
        max_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
        Fs = 0;
        use_vbr = 0;
        vbr_constraint = 0;
        variable_duration = OpusFramesize.OPUS_FRAMESIZE_UNKNOWN;
        bitrate_bps = 0;
        user_bitrate_bps = 0;
        lsb_depth = 0;
        lfe = 0;
        analysis.Reset();
        PartialReset();
    }

    /// <summary>
    /// OPUS_ENCODER_RESET_START
    /// </summary>
    void PartialReset() {
        stream_channels = 0;
        hybrid_stereo_width_Q14 = 0;
        Arrays.fill(hp_mem, 0, 4, 0);
        prev_framesize = 0;
        bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
        first = 0;
        energy_masking = null;
        detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
        rangeFinal = 0;
        //CeltEncoder.Reset();
    }

    public void resetState() {
        analysis.Reset();
        PartialReset();

        Celt_Encoder.ResetState();
        stream_channels = channels;
        hybrid_stereo_width_Q14 = 1 << 14;
        first = 1;
        bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;
    }

    /**
     * Allocates and initializes an encoder state. Note that regardless of the
     * sampling rate and number channels selected, the Opus encoder can switch
     * to a lower audio bandwidth or number of channels if the bitrate selected
     * is too low. This also means that it is safe to always use 48 kHz stereo
     * input and let the encoder optimize the encoding. The decoder will not be
     * constrained later on by the mode that you select here for the encoder.
     *
     * @param Fs          Sampling rate of input signal (Hz). This must be one of 8000,
     *                    12000, 16000, 24000, or 48000.
     * @param channels    Number of channels (1 or 2) in input signal
     */
    public OpusEncoder(int Fs, int channels) throws OpusException {
        int ret;
        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000)) {
            throw new IllegalArgumentException("Sample rate is invalid (must be 8/12/16/24/48 Khz)");
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("Number of channels must be 1 or 2");
        }

        ret = opus_init_encoder(Fs, channels);
        if (ret != OpusError.OPUS_OK) {
            if (ret == OpusError.OPUS_BAD_ARG) {
                throw new IllegalArgumentException("OPUS_BAD_ARG when creating encoder");
            }
            throw new OpusException("Error while initializing encoder", ret);
        }
    }

    int opus_init_encoder(int Fs, int channels) {
        CeltEncoder celt_enc;
        int err;

        if ((Fs != 48000 && Fs != 24000 && Fs != 16000 && Fs != 12000 && Fs != 8000) || (channels != 1 && channels != 2)) {
            return OpusError.OPUS_BAD_ARG;
        }

        reset();
        celt_enc = Celt_Encoder;

        this.stream_channels = this.channels = channels;

        this.Fs = Fs;

        /* default SILK parameters */
        silk_mode.packetLossPercentage = 0;
        silk_mode.complexity = 9;
        silk_mode.reducedDependency = 0;

        /* Create CELT encoder */
        /* Initialize CELT encoder */
        err = celt_enc.celt_encoder_init(Fs, channels);
        if (err != OpusError.OPUS_OK) {
            return OpusError.OPUS_INTERNAL_ERROR;
        }

        celt_enc.SetComplexity(silk_mode.complexity);

        this.use_vbr = 1;
        /* Makes constrained VBR the default (safer for real-time use) */
        this.vbr_constraint = 1;
        this.user_bitrate_bps = OpusConstants.OPUS_AUTO;
        this.bitrate_bps = 3000 + Fs * channels;
        this.user_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_AUTO;
        this.max_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;
        this.force_channels = OpusConstants.OPUS_AUTO;
        this.lsb_depth = 24;
        this.variable_duration = OpusFramesize.OPUS_FRAMESIZE_ARG;

        this.hybrid_stereo_width_Q14 = 1 << 14;
        this.first = 1;
        this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;

        Analysis.tonality_analysis_init(analysis);

        return OpusError.OPUS_OK;
    }

    int user_bitrate_to_bitrate(int frame_size, int max_data_bytes) {
        if (frame_size == 0) {
            frame_size = Fs / 400;
        }
        if (user_bitrate_bps == OpusConstants.OPUS_AUTO) {
            return 60 * Fs / frame_size + Fs * channels;
        } else if (user_bitrate_bps == OpusConstants.OPUS_BITRATE_MAX) {
            return max_data_bytes * 8 * Fs / frame_size;
        } else {
            return user_bitrate_bps;
        }
    }

    /// <summary>
    ///
    /// </summary>
    /// <typeparam name="T">The storage type of analysis_pcm, either short or float</typeparam>
    /// <param name="this"></param>
    /// <param name="pcm"></param>
    /// <param name="frame_size"></param>
    /// <param name="data"></param>
    /// <param name="out_data_bytes"></param>
    /// <param name="lsb_depth"></param>
    /// <param name="analysis_pcm"></param>
    /// <param name="analysis_size"></param>
    /// <param name="c1"></param>
    /// <param name="c2"></param>
    /// <param name="analysis_channels"></param>
    /// <param name="downmix"></param>
    /// <param name="float_api"></param>
    /// <returns></returns>
    int opus_encode_native(short[] pcm, int pcm_ptr, int frame_size,
                           byte[] data, int data_ptr, int out_data_bytes, int lsb_depth,
                           short[] analysis_pcm, int analysis_pcm_ptr, int analysis_size, int c1, int c2,
                           int analysis_channels) {
        CeltEncoder celt_enc;
        int i;
        int ret = 0;
        EntropyCoder enc = new EntropyCoder(); // porting note: stack var
        int bytes_target;
        short[] pcm_buf;
        int nb_compr_bytes;
        int equiv_rate;
        int frame_rate;
        OpusBandwidth curr_bandwidth;
        int max_data_bytes;
        /* Max number of bytes we're allowed to use */
        CeltMode celt_mode;
        AnalysisInfo analysis_info = new AnalysisInfo(); // porting note: stack var
        int analysis_read_pos_bak = -1;
        int analysis_read_subframe_bak = -1;

        max_data_bytes = Inlines.IMIN(1276, out_data_bytes);

        this.rangeFinal = 0;
        if ((variable_duration == OpusFramesize.OPUS_FRAMESIZE_UNKNOWN && 400 * frame_size != Fs && 200 * frame_size != Fs && 100 * frame_size != Fs
                && 50 * frame_size != Fs && 25 * frame_size != Fs && 50 * frame_size != 3 * Fs)
                || (400 * frame_size < Fs)
                || max_data_bytes <= 0) {
            return OpusError.OPUS_BAD_ARG;
        }

        celt_enc = Celt_Encoder;

        lsb_depth = Inlines.IMIN(lsb_depth, this.lsb_depth);
        celt_mode = celt_enc.GetMode();

        if (analysis.enabled) {
            analysis_info.valid = 0;
            if (silk_mode.complexity >= 7 && Fs == 48000) {
                analysis_read_pos_bak = analysis.read_pos;
                analysis_read_subframe_bak = analysis.read_subframe;
                Analysis.run_analysis(analysis,
                        celt_mode,
                        analysis_pcm,
                        analysis_pcm_ptr,
                        analysis_size,
                        frame_size,
                        c1,
                        c2,
                        analysis_channels,
                        Fs,
                        lsb_depth,
                        analysis_info);
            }

            this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN;
            if (analysis_info.valid != 0) {
                int analysis_bandwidth;
                analysis_bandwidth = analysis_info.bandwidth;
                if (analysis_bandwidth <= 12) {
                    this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;
                } else if (analysis_bandwidth <= 14) {
                    this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND;
                } else if (analysis_bandwidth <= 16) {
                    this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND;
                } else if (analysis_bandwidth <= 18) {
                    this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_SUPERWIDEBAND;
                } else {
                    this.detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;
                }
            }
        }

        this.bitrate_bps = user_bitrate_to_bitrate(frame_size, max_data_bytes);

        frame_rate = Fs / frame_size;
        if (use_vbr == 0) {
            int cbrBytes;
            /* Multiply by 3 to make sure the division is exact. */
            int frame_rate3 = 3 * Fs / frame_size;
            /* We need to make sure that "int" values always fit in 16 bits. */
            cbrBytes = Inlines.IMIN((3 * bitrate_bps / 8 + frame_rate3 / 2) / frame_rate3, max_data_bytes);
            this.bitrate_bps = cbrBytes * frame_rate3 * 8 / 3;
            max_data_bytes = cbrBytes;
        }
        if (max_data_bytes < 3 || bitrate_bps < 3 * frame_rate * 8
                || (frame_rate < 50 && (max_data_bytes * frame_rate < 300 || bitrate_bps < 2400))) {
            /*If the space is too low to do something useful, emit 'PLC' frames.*/
            OpusBandwidth bw = bandwidth == OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN ? OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND : bandwidth;
            if (bw == OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND) {
                bw = OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;
            }
            if (frame_rate < 50) {
                /* CELT has no single-frame TOC for >20 ms, so signal a code 3
                   packet holding nb_frames empty (PLC) 20 ms CELT frames */
                if (max_data_bytes < 2) {
                    return OpusError.OPUS_BUFFER_TOO_SMALL;
                }
                data[data_ptr] = (byte) (CodecHelpers.gen_toc(50, bw, stream_channels) | 0x03);
                data[data_ptr + 1] = (byte) (50 / frame_rate);
                ret = 2;
            } else {
                data[data_ptr] = CodecHelpers.gen_toc(frame_rate, bw, stream_channels);
                ret = 1;
            }
            if (use_vbr == 0) {
                ret = OpusRepacketizer.padPacket(data, data_ptr, ret, max_data_bytes);
                if (ret == OpusError.OPUS_OK) {
                    ret = max_data_bytes;
                }
            }
            return ret;
        }

        /* Equivalent 20-ms rate for mode/channel/bandwidth decisions */
        equiv_rate = bitrate_bps - (40 * channels + 20) * (Fs / frame_size - 50);

        if (force_channels != OpusConstants.OPUS_AUTO && channels == 2) {
            this.stream_channels = force_channels;
        } else /* Rate-dependent mono-stereo decision */ if (channels == 2) {
            int stereo_threshold = OpusTables.stereo_threshold;
            if (stream_channels == 2) {
                stereo_threshold -= 1000;
            } else {
                stereo_threshold += 1000;
            }
            this.stream_channels = (equiv_rate > stereo_threshold) ? 2 : 1;
        } else {
            this.stream_channels = channels;
        }
        equiv_rate = bitrate_bps - (40 * stream_channels + 20) * (Fs / frame_size - 50);

        /* Automatic (rate-dependent) bandwidth selection */
        {
            int[] bandwidth_thresholds;
            OpusBandwidth bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;
            int equiv_rate2;

            equiv_rate2 = equiv_rate;
            bandwidth_thresholds = (channels == 2 && force_channels != 1)
                    ? OpusTables.stereo_bandwidth_thresholds
                    : OpusTables.mono_bandwidth_thresholds;
            do {
                int threshold, hysteresis;
                threshold = bandwidth_thresholds[2 * (OpusBandwidthHelpers.GetOrdinal(bandwidth) - OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND))];
                hysteresis = bandwidth_thresholds[2 * (OpusBandwidthHelpers.GetOrdinal(bandwidth) - OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND)) + 1];
                if (first == 0) {
                    if (OpusBandwidthHelpers.GetOrdinal(this.bandwidth) >= OpusBandwidthHelpers.GetOrdinal(bandwidth)) {
                        threshold -= hysteresis;
                    } else {
                        threshold += hysteresis;
                    }
                }
                if (equiv_rate2 >= threshold) {
                    break;
                }

                bandwidth = OpusBandwidthHelpers.SUBTRACT(bandwidth, 1);
            } while (OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND));
            this.bandwidth = bandwidth;
        }

        if (OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(max_bandwidth)) {
            this.bandwidth = max_bandwidth;
        }

        if (user_bandwidth != OpusBandwidth.OPUS_BANDWIDTH_AUTO) {
            this.bandwidth = user_bandwidth;
        }

        /* Prevents Opus from wasting bits on frequencies that are above
           the Nyquist rate of the input signal */
        if (Fs <= 24000 && OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_SUPERWIDEBAND)) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_SUPERWIDEBAND;
        }
        if (Fs <= 16000 && OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND)) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND;
        }
        if (Fs <= 12000 && OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND)) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND;
        }
        if (Fs <= 8000 && OpusBandwidthHelpers.GetOrdinal(bandwidth) > OpusBandwidthHelpers.GetOrdinal(OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND)) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;
        }
        /* Use detected bandwidth to reduce the encoded bandwidth. */
        if (detected_bandwidth != OpusBandwidth.OPUS_BANDWIDTH_UNKNOWN && user_bandwidth == OpusBandwidth.OPUS_BANDWIDTH_AUTO) {
            OpusBandwidth min_detected_bandwidth;
            /* Makes bandwidth detection more conservative just in case the detector
               gets it wrong when we could have coded a high bandwidth transparently. */
            if (equiv_rate <= 18000 * stream_channels) {
                min_detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;
            } else if (equiv_rate <= 24000 * stream_channels) {
                min_detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND;
            } else if (equiv_rate <= 30000 * stream_channels) {
                min_detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND;
            } else if (equiv_rate <= 44000 * stream_channels) {
                min_detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_SUPERWIDEBAND;
            } else {
                min_detected_bandwidth = OpusBandwidth.OPUS_BANDWIDTH_FULLBAND;
            }

            this.detected_bandwidth = OpusBandwidthHelpers.MAX(detected_bandwidth, min_detected_bandwidth);
            this.bandwidth = OpusBandwidthHelpers.MIN(bandwidth, detected_bandwidth);
        }
        celt_enc.SetLSBDepth(lsb_depth);

        /* CELT mode doesn't support mediumband, use wideband instead */
        if (bandwidth == OpusBandwidth.OPUS_BANDWIDTH_MEDIUMBAND) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND;
        }
        if (lfe != 0) {
            this.bandwidth = OpusBandwidth.OPUS_BANDWIDTH_NARROWBAND;
        }

        /* CELT can't encode >20 ms frames directly, split into 20 ms frames and repacketize */
        if (frame_size > Fs / 50) {
            byte[] tmp_data;
            int nb_frames;
            OpusBandwidth bak_bandwidth;
            int bak_channels;
            OpusRepacketizer rp;
            int bytes_per_frame;
            int repacketize_len;

            if (analysis.enabled && analysis_read_pos_bak != -1) {
                analysis.read_pos = analysis_read_pos_bak;
                analysis.read_subframe = analysis_read_subframe_bak;
            }

            nb_frames = frame_size > Fs / 25 ? 3 : 2;
            bytes_per_frame = Inlines.IMIN(1276, (out_data_bytes - 3) / nb_frames);

            tmp_data = new byte[nb_frames * bytes_per_frame];

            rp = new OpusRepacketizer();

            bak_bandwidth = user_bandwidth;
            bak_channels = force_channels;

            this.user_bandwidth = bandwidth;
            this.force_channels = stream_channels;

            for (i = 0; i < nb_frames; i++) {
                int tmp_len;
                tmp_len = opus_encode_native(pcm, pcm_ptr + (i * (channels * Fs / 50)), Fs / 50,
                        tmp_data, i * bytes_per_frame, bytes_per_frame, lsb_depth,
                        null, 0, 0, c1, c2, analysis_channels);
                if (tmp_len < 0) {

                    return OpusError.OPUS_INTERNAL_ERROR;
                }
                ret = rp.addPacket(tmp_data, i * bytes_per_frame, tmp_len);
                if (ret < 0) {

                    return OpusError.OPUS_INTERNAL_ERROR;
                }
            }
            if (use_vbr != 0) {
                repacketize_len = out_data_bytes;
            } else {
                repacketize_len = Inlines.IMIN(3 * bitrate_bps / (3 * 8 * 50 / nb_frames), out_data_bytes);
            }
            ret = rp.opus_repacketizer_out_range_impl(0, nb_frames, data, data_ptr, repacketize_len, 0, (use_vbr == 0) ? 1 : 0);
            if (ret < 0) {
                return OpusError.OPUS_INTERNAL_ERROR;
            }
            this.user_bandwidth = bak_bandwidth;
            this.force_channels = bak_channels;

            return ret;
        }
        curr_bandwidth = bandwidth;

        bytes_target = Inlines.IMIN(max_data_bytes, bitrate_bps * frame_size / (Fs * 8)) - 1;

        data_ptr += 1;

        enc.enc_init(data, data_ptr, (max_data_bytes - 1));

        pcm_buf = new short[frame_size * channels];
        CodecHelpers.dc_reject(pcm, pcm_ptr, 3, pcm_buf, 0, hp_mem, frame_size, channels, Fs);

        /* CELT processing */
        {
            int endband = 21;

            switch (curr_bandwidth) {
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
            celt_enc.SetEndBand(endband);
            celt_enc.SetChannels(stream_channels);
        }
        celt_enc.SetBitrate(OpusConstants.OPUS_BITRATE_MAX);
        celt_enc.SetVBR(false);
        /* We may still decide to disable prediction later */
        celt_enc.SetPrediction(silk_mode.reducedDependency != 0 ? 0 : 2);

        if (use_vbr != 0) {
            int bonus = 0;
            if (analysis.enabled && variable_duration == OpusFramesize.OPUS_FRAMESIZE_VARIABLE && frame_size != Fs / 50) {
                bonus = (60 * stream_channels + 40) * (Fs / frame_size - 50);
                if (analysis_info.valid != 0) {
                    bonus = (int) (bonus * (1.0f + .5f * analysis_info.tonality));
                }
            }
            celt_enc.SetVBR(true);
            celt_enc.SetVBRConstraint(vbr_constraint != 0);
            celt_enc.SetBitrate(bitrate_bps + bonus);
            nb_compr_bytes = max_data_bytes - 1;
        } else {
            nb_compr_bytes = bytes_target;
        }

        if (energy_masking == null && channels == 2) {
            /* Apply stereo width reduction (at low bitrates) */
            int stereo_width_Q14 = Inlines.IMIN((1 << 14), 2 * Inlines.IMAX(0, equiv_rate - 30000));
            if (hybrid_stereo_width_Q14 < (1 << 14) || stereo_width_Q14 < (1 << 14)) {
                int g1, g2;
                g1 = hybrid_stereo_width_Q14;
                g2 = stereo_width_Q14;
                g1 = g1 == 16384 ? CeltConstants.Q15ONE : Inlines.SHL16(g1, 1);
                g2 = g2 == 16384 ? CeltConstants.Q15ONE : Inlines.SHL16(g2, 1);
                CodecHelpers.stereo_fade(pcm_buf, g1, g2, celt_mode.overlap,
                        frame_size, channels, celt_mode.window, Fs);
                this.hybrid_stereo_width_Q14 = (short) stereo_width_Q14;
            }
        }

        nb_compr_bytes = Inlines.IMIN(max_data_bytes - 1, nb_compr_bytes);
        enc.enc_shrink(nb_compr_bytes);

        analysis_info.enabled = analysis.enabled;
        celt_enc.SetAnalysis(analysis_info);

        /* If false, we already busted the budget and we'll end up with a "PLC packet" */
        if (enc.tell() <= 8 * nb_compr_bytes) {
            ret = celt_enc.celt_encode_with_ec(pcm_buf, 0, frame_size, null, 0, nb_compr_bytes, enc);
            if (ret < 0) {
                return OpusError.OPUS_INTERNAL_ERROR;
            }
        }

        /* Signalling the mode in the first byte */
        data_ptr -= 1;
        data[data_ptr] = CodecHelpers.gen_toc(Fs / frame_size, curr_bandwidth, stream_channels);

        this.rangeFinal = (int) enc.rng;

        this.prev_framesize = frame_size;

        this.first = 0;

        /* In the unlikely case that the encoder busted its target, tell
           the decoder to call the PLC */
        if (enc.tell() > (max_data_bytes - 1) * 8) {
            data[data_ptr + 1] = 0;
            ret = 1;
            this.rangeFinal = 0;
        }
        /* Count ToC */
        ret += 1;
        if (use_vbr == 0) {
            if (OpusRepacketizer.padPacket(data, data_ptr, ret, max_data_bytes) != OpusError.OPUS_OK) {
                return OpusError.OPUS_INTERNAL_ERROR;
            }
            ret = max_data_bytes;
        }

        return ret;
    }

    /**
     * Encodes an Opus frame, putting the output into a specified data buffer
     *
     * @param in_pcm          16-bit input signal (Interleaved if stereo), in a short array. Length should be at least frame_size * channels
     * @param pcm_offset      Offset to use when reading the in_pcm buffer
     * @param frame_size      The number of samples _per channel_ in the inpus signal. The frame size must be a valid Opus framesize for the given sample rate.
     *                        For example, at 48Khz the permitted values are 120, 240, 480, 960, 1920, and 2880. Passing in a duration of less than 10ms
     *                        (480 samples at 48Khz) will prevent the encoder from using FEC, DTX, or hybrid modes.
     * @param out_data        Destination buffer for the output payload. This must contain at least max_data_bytes
     * @param out_data_offset The offset to use when writing to the output data buffer
     * @param max_data_bytes  The maximum amount of space allocated for the output payload. This may be used to impose
     *                        an upper limit on the instant bitrate, but should not be used as the only bitrate control (use setBitrate for that)
     * @return The length of the encoded packet, in bytes
     */
    public int encode(short[] in_pcm, int pcm_offset, int frame_size,
                      byte[] out_data, int out_data_offset, int max_data_bytes) throws OpusException {
        // Check that the caller is telling the truth about its input buffers
        if (out_data_offset + max_data_bytes > out_data.length) {
            throw new IllegalArgumentException("Output buffer is too small: Stated size is " + max_data_bytes + " bytes, actual size is " + (out_data.length - out_data_offset) + " bytes");
        }

        int internal_frame_size = CodecHelpers.compute_frame_size(in_pcm, pcm_offset, frame_size,
                variable_duration, channels, Fs, bitrate_bps,
                analysis.subframe_mem, analysis.enabled);

        // Check that input pcm length is >= frame_size
        if (pcm_offset + internal_frame_size > in_pcm.length) {
            throw new IllegalArgumentException("Not enough samples provided in input signal: Expected " + internal_frame_size + " samples, found " + (in_pcm.length - pcm_offset));
        }

        try {
            int ret = opus_encode_native(in_pcm, pcm_offset, internal_frame_size, out_data, out_data_offset, max_data_bytes, 16,
                    in_pcm, pcm_offset, frame_size, 0, -2, channels);

            if (ret < 0) {
                // An error happened; report it
                if (ret == OpusError.OPUS_BAD_ARG) {
                    throw new IllegalArgumentException("OPUS_BAD_ARG while encoding");
                }
                throw new OpusException("An error occurred during encoding", ret);
            }

            return ret;
        } catch (ArithmeticException e) {
            throw new OpusException("Internal error during encoding: " + e.getMessage());
        }
    }

    /**
     * Encodes an Opus frame, putting the output into a specified data buffer
     *
     * @param in_pcm          16-bit input signal (Interleaved if stereo), in a little endian byte array. Length should be at least frame_size * channels * 2
     * @param pcm_offset      Offset to use when reading the in_pcm buffer
     * @param frame_size      The number of samples _per channel_ in the inpus signal. The frame size must be a valid Opus framesize for the given sample rate.
     *                        For example, at 48Khz the permitted values are 120, 240, 480, 960, 1920, and 2880. Passing in a duration of less than 10ms
     *                        (480 samples at 48Khz) will prevent the encoder from using FEC, DTX, or hybrid modes.
     * @param out_data        Destination buffer for the output payload. This must contain at least max_data_bytes
     * @param out_data_offset The offset to use when writing to the output data buffer
     * @param max_data_bytes  The maximum amount of space allocated for the output payload. This may be used to impose
     *                        an upper limit on the instant bitrate, but should not be used as the only bitrate control (use setBitrate for that)
     * @return The length of the encoded packet, in bytes
     */
    public int encode(byte[] in_pcm, int pcm_offset, int frame_size,
                      byte[] out_data, int out_data_offset, int max_data_bytes) throws OpusException {
        //Convert byte array to short array
        short[] spcm = new short[frame_size * channels];
        for (int c = 0, idx = pcm_offset; c < spcm.length; idx += 2, c++) {
            spcm[c] = (short) (((in_pcm[idx] & 0xff) | (in_pcm[idx + 1] << 8)) & 0xffff);
        }
        return encode(spcm, 0, frame_size, out_data, out_data_offset, max_data_bytes);
    }

    /// <summary>
    /// Gets or sets the bitrate for encoder, in bits per second. Valid bitrates are be between 6K (6144) and 510K (522240)
    /// </summary>
    public int getBitrate() {
        return user_bitrate_to_bitrate(prev_framesize, 1276);
    }

    public void setBitrate(int value) {
        if (value != OpusConstants.OPUS_AUTO && value != OpusConstants.OPUS_BITRATE_MAX) {
            if (value <= 0) {
                throw new IllegalArgumentException("Bitrate must be positive");
            } else if (value <= 500) {
                value = 500;
            } else if (value > 300000 * channels) {
                value = 300000 * channels;
            }
        }

        user_bitrate_bps = value;
    }

    /// <summary>
    /// Gets or sets the maximum number of channels to be encoded. This can be used to force a downmix from stereo to mono if stereo
    /// separation is not important
    /// </summary>
    public int getForceChannels() {
        return force_channels;
    }

    public void setForceChannels(int value) {
        if ((value < 1 || value > channels) && value != OpusConstants.OPUS_AUTO) {
            throw new IllegalArgumentException("Force channels must be <= num. of channels");
        }

        force_channels = value;
    }

    /// <summary>
    /// Gets or sets the maximum bandwidth to be used by the encoder.
    /// </summary>
    public OpusBandwidth getMaxBandwidth() {
        return max_bandwidth;
    }

    public void setMaxBandwidth(OpusBandwidth value) {
        max_bandwidth = value;
    }

    /// <summary>
    /// Gets or sets the "preferred" encoded bandwidth
    /// </summary>
    public OpusBandwidth getBandwidth() {
        return bandwidth;
    }

    public void setBandwidth(OpusBandwidth value) {
        user_bandwidth = value;
    }

    /// <summary>
    /// Gets or sets the encoder complexity, between 0 and 10
    /// </summary>
    public int getComplexity() {
        return silk_mode.complexity;
    }

    public void setComplexity(int value) {
        if (value < 0 || value > 10) {
            throw new IllegalArgumentException("Complexity must be between 0 and 10");
        }
        silk_mode.complexity = value;
        Celt_Encoder.SetComplexity(value);
    }

    /// <summary>
    /// Gets or sets the expected amount of packet loss in the transmission medium, from 0 to 100.
    /// Only applies if UseInbandFEC is also enabled, and the encoder is in SILK mode.
    /// </summary>
    public int getPacketLossPercent() {
        return silk_mode.packetLossPercentage;
    }

    public void setPacketLossPercent(int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Packet loss must be between 0 and 100");
        }
        silk_mode.packetLossPercentage = value;
        Celt_Encoder.SetPacketLossPercent(value);
    }

    /// <summary>
    /// Gets or sets a flag to enable Variable Bitrate encoding. This is recommended as it generally improves audio quality
    /// with little impact on average bitrate
    /// </summary>
    public boolean getUseVBR() {
        return use_vbr != 0;
    }

    public void setUseVBR(boolean value) {
        use_vbr = value ? 1 : 0;
    }

    /// <summary>
    /// Gets or sets a flag to enable constrained VBR. This only applies when the encoder is in CELT mode (i.e. high bitrates)
    /// </summary>
    public boolean getUseConstrainedVBR() {
        return vbr_constraint != 0;
    }

    public void setUseConstrainedVBR(boolean value) {
        vbr_constraint = value ? 1 : 0;
    }

    /// <summary>
    /// Gets the number of samples of audio that are being stored in a buffer and are therefore contributing to latency.
    /// </summary>
    public int getLookahead() {
        return Fs / 400;
    }

    /// <summary>
    /// Gets the encoder's input sample rate.
    /// </summary>
    public int getSampleRate() {
        return Fs;
    }

    public int getFinalRange() {
        return rangeFinal;
    }

    /// <summary>
    /// Gets or sets the bit resolution of the input audio signal. Though the encoder always uses 16-bit internally, this can help
    /// it make better decisions about bandwidth and cutoff values
    /// </summary>
    public int getLSBDepth() {
        return lsb_depth;
    }

    public void setLSBDepth(int value) {
        if (value < 8 || value > 24) {
            throw new IllegalArgumentException("LSB depth must be between 8 and 24");
        }

        lsb_depth = value;
    }

    /// <summary>
    /// Gets or sets a fixed length for each encoded frame. Typically, the encoder just chooses a frame duration based on the input length
    /// and the current mode. This can be used to enforce an exact length if it is required by your application (e.g. monotonous transmission)
    /// </summary>
    public OpusFramesize getExpertFrameDuration() {
        return variable_duration;
    }

    public void setExpertFrameDuration(OpusFramesize value) {
        variable_duration = value;
        Celt_Encoder.SetExpertFrameDuration(value);
    }

    /// <summary>
    /// Gets or sets a value indicating that this stream is a low-frequency channel. This is used when encoding 5.1 surround audio.
    /// </summary>
    public boolean getIsLFE() {
        return lfe != 0;
    }

    public void setIsLFE(boolean value) {
        lfe = value ? 1 : 0;
        Celt_Encoder.SetLFE(value ? 1 : 0);
    }

    /// <summary>
    /// Gets or sets a flag to enable prediction, which does something.
    /// </summary>
    public boolean getPredictionDisabled() {
        return silk_mode.reducedDependency != 0;
    }

    public void setPredictionDisabled(boolean value) {
        silk_mode.reducedDependency = value ? 1 : 0;
    }

    /// <summary>
    /// Gets or sets a value indicating whether neural net analysis functions should be enabled, increasing encode quality
    /// at the expense of speed.
    /// </summary>
    public boolean getEnableAnalysis() {
        return analysis.enabled;
    }

    public void setEnableAnalysis(boolean value) {
        analysis.enabled = value;
    }

    void SetEnergyMask(int[] value) {
        energy_masking = value;
        Celt_Encoder.SetEnergyMask(value);
    }

    CeltMode GetCeltMode() {
        return Celt_Encoder.GetMode();
    }
}
