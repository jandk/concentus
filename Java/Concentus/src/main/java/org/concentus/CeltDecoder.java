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
/// Decoder state
/// </summary>
class CeltDecoder {

    CeltMode mode = null;
    int overlap = 0;
    int channels = 0;
    int stream_channels = 0;

    int downsample = 0;
    int end = 0;

    /* Everything beyond this point gets cleared on a reset */
    int rng = 0;
    int error = 0;
    int postfilter_period = 0;
    int postfilter_period_old = 0;
    int postfilter_gain = 0;
    int postfilter_gain_old = 0;
    int postfilter_tapset = 0;
    int postfilter_tapset_old = 0;

    final int[] preemph_memD = new int[2];

    /// <summary>
    /// Scratch space used by the decoder. It is actually a variable-sized
    /// field that resulted in a variable-sized struct. There are 6 distinct regions inside.
    /// I have laid them out into separate variables here,
    /// but these were the original definitions:
    /// val32 decode_mem[],     Size = channels*(DECODE_BUFFER_SIZE+mode.overlap)
    /// val16 oldEBands[],      Size = 2*mode.nbEBands
    /// val16 oldLogE[],        Size = 2*mode.nbEBands
    /// val16 oldLogE2[],       Size = 2*mode.nbEBands
    /// </summary>
    int[][] decode_mem = null;
    int[] oldEBands = null;
    int[] oldLogE = null;
    int[] oldLogE2 = null;

    private void Reset() {
        mode = null;
        overlap = 0;
        channels = 0;
        stream_channels = 0;
        downsample = 0;
        end = 0;
        PartialReset();
    }

    private void PartialReset() {
        rng = 0;
        error = 0;
        postfilter_period = 0;
        postfilter_period_old = 0;
        postfilter_gain = 0;
        postfilter_gain_old = 0;
        postfilter_tapset = 0;
        postfilter_tapset_old = 0;
        Arrays.fill(preemph_memD, 0, 2, 0);
        decode_mem = null;
        oldEBands = null;
        oldLogE = null;
        oldLogE2 = null;
    }

    void ResetState() {
        int i;

        PartialReset();

        // We have to reconstitute the dynamic buffers here. fixme: this could be better implemented
        this.decode_mem = new int[channels][];
        for (int c = 0; c < channels; c++) {
            decode_mem[c] = new int[CeltConstants.DECODE_BUFFER_SIZE + mode.overlap];
        }
        this.oldEBands = new int[2 * mode.nbEBands];
        this.oldLogE = new int[2 * mode.nbEBands];
        this.oldLogE2 = new int[2 * mode.nbEBands];

        for (i = 0; i < 2 * mode.nbEBands; i++) {
            oldLogE[i] = oldLogE2[i] = -Inlines.QCONST16(28.0f, CeltConstants.DB_SHIFT);
        }
    }

    int celt_decoder_init(int sampling_rate, int channels) {
        int ret;
        ret = opus_custom_decoder_init(CeltMode.mode48000_960_120, channels);
        if (ret != OpusError.OPUS_OK) {
            return ret;
        }
        this.downsample = CeltCommon.resampling_factor(sampling_rate);
        if (downsample == 0) {
            return OpusError.OPUS_BAD_ARG;
        } else {
            return OpusError.OPUS_OK;
        }
    }

    private int opus_custom_decoder_init(CeltMode mode, int channels) {
        if (channels < 0 || channels > 2) {
            return OpusError.OPUS_BAD_ARG;
        }

        Reset();

        this.mode = mode;
        this.overlap = mode.overlap;
        this.stream_channels = this.channels = channels;

        this.downsample = 1;
        this.end = this.mode.effEBands;

        //this.decode_mem = new int[channels * (CeltConstants.DECODE_BUFFER_SIZE + mode.overlap));
        //this.oldEBands = new int[2 * mode.nbEBands);
        //this.oldLogE = new int[2 * mode.nbEBands);
        //this.oldLogE2 = new int[2 * mode.nbEBands);
        ResetState();

        return OpusError.OPUS_OK;
    }

    int celt_decode_with_ec(byte[] data, int data_ptr,
                            int len, short[] pcm, int pcm_ptr, int frame_size, EntropyCoder dec) {
        int c, i, N;
        int spread_decision;
        int bits;
        int[][] X;
        int[] fine_quant;
        int[] pulses;
        int[] cap;
        int[] offsets;
        int[] fine_priority;
        int[] tf_res;
        short[] collapse_masks;
        int[][] out_syn = new int[2][];
        int[] out_syn_ptrs = new int[2];
        int[] oldBandE, oldLogE, oldLogE2;

        int shortBlocks;
        int isTransient;
        int intra_ener;
        int CC = channels;
        int LM, M;
        int end;
        int effEnd;
        int codedBands;
        int alloc_trim;
        int postfilter_pitch;
        int postfilter_gain;
        int intensity = 0;
        int dual_stereo = 0;
        int total_bits;
        int balance;
        int tell;
        int dynalloc_logp;
        int postfilter_tapset;
        int anti_collapse_rsv;
        int anti_collapse_on = 0;
        int silence;
        int C = stream_channels;
        CeltMode mode; // porting note: pointer
        int nbEBands;
        int overlap;
        short[] eBands;

        mode = this.mode;
        nbEBands = mode.nbEBands;
        overlap = mode.overlap;
        eBands = mode.eBands;
        end = this.end;
        frame_size *= downsample;

        oldBandE = oldEBands;
        oldLogE = this.oldLogE;
        oldLogE2 = this.oldLogE2;

        {
            for (LM = 0; LM <= mode.maxLM; LM++) {
                if (mode.shortMdctSize << LM == frame_size) {
                    break;
                }
            }
            if (LM > mode.maxLM) {
                return OpusError.OPUS_BAD_ARG;
            }
        }
        M = 1 << LM;

        if (len < 0 || len > 1275 || pcm == null) {
            return OpusError.OPUS_BAD_ARG;
        }

        N = M * mode.shortMdctSize;
        c = 0;
        do {
            out_syn[c] = decode_mem[c];
            out_syn_ptrs[c] = CeltConstants.DECODE_BUFFER_SIZE - N;
        } while (++c < CC);

        effEnd = end;
        if (effEnd > mode.effEBands) {
            effEnd = mode.effEBands;
        }

        if (dec == null) {
            // If no entropy decoder was passed into this function, we need to create
            // a new one here for local use only. It only exists in this function scope.
            dec = new EntropyCoder();
            dec.dec_init(data, data_ptr, len);
        }

        if (C == 1) {
            for (i = 0; i < nbEBands; i++) {
                oldBandE[i] = Inlines.MAX16(oldBandE[i], oldBandE[nbEBands + i]);
            }
        }

        total_bits = len * 8;
        tell = dec.tell();

        if (tell >= total_bits) {
            silence = 1;
        } else if (tell == 1) {
            silence = dec.dec_bit_logp(15);
        } else {
            silence = 0;
        }

        if (silence != 0) {
            /* Pretend we've read all the remaining bits */
            tell = len * 8;
            dec.nbits_total += tell - dec.tell();
        }

        postfilter_gain = 0;
        postfilter_pitch = 0;
        postfilter_tapset = 0;
        if (tell + 16 <= total_bits) {
            if (dec.dec_bit_logp(1) != 0) {
                int qg, octave;
                octave = (int) dec.dec_uint(6);
                postfilter_pitch = (16 << octave) + dec.dec_bits(4 + octave) - 1;
                qg = dec.dec_bits(3);
                if (dec.tell() + 2 <= total_bits) {
                    postfilter_tapset = dec.dec_icdf(CeltTables.tapset_icdf, 2);
                }
                postfilter_gain = Inlines.QCONST16(.09375f, 15) * (qg + 1);
            }
            tell = dec.tell();
        }

        if (LM > 0 && tell + 3 <= total_bits) {
            isTransient = dec.dec_bit_logp(3);
            tell = dec.tell();
        } else {
            isTransient = 0;
        }

        if (isTransient != 0) {
            shortBlocks = M;
        } else {
            shortBlocks = 0;
        }

        /* Decode the global flags (first symbols in the stream) */
        intra_ener = tell + 3 <= total_bits ? dec.dec_bit_logp(3) : 0;
        /* Get band energies */
        QuantizeBands.unquant_coarse_energy(mode, 0, end, oldBandE,
                intra_ener, dec, C, LM);

        tf_res = new int[nbEBands];
        CeltCommon.tf_decode(0, end, isTransient, tf_res, LM, dec);

        tell = dec.tell();
        spread_decision = Spread.SPREAD_NORMAL;
        if (tell + 4 <= total_bits) {
            spread_decision = dec.dec_icdf(CeltTables.spread_icdf, 5);
        }

        cap = new int[nbEBands];

        CeltCommon.init_caps(mode, cap, LM, C);

        offsets = new int[nbEBands];

        dynalloc_logp = 6;
        total_bits <<= EntropyCoder.BITRES;
        tell = dec.tell_frac();
        for (i = 0; i < end; i++) {
            int width, quanta;
            int dynalloc_loop_logp;
            int boost;
            width = C * (eBands[i + 1] - eBands[i]) << LM;
            /* quanta is 6 bits, but no more than 1 bit/sample
               and no less than 1/8 bit/sample */
            quanta = Inlines.IMIN(width << EntropyCoder.BITRES, Inlines.IMAX(6 << EntropyCoder.BITRES, width));
            dynalloc_loop_logp = dynalloc_logp;
            boost = 0;
            while (tell + (dynalloc_loop_logp << EntropyCoder.BITRES) < total_bits && boost < cap[i]) {
                int flag;
                flag = dec.dec_bit_logp(dynalloc_loop_logp);
                tell = dec.tell_frac();
                if (flag == 0) {
                    break;
                }
                boost += quanta;
                total_bits -= quanta;
                dynalloc_loop_logp = 1;
            }
            offsets[i] = boost;
            /* Making dynalloc more likely */
            if (boost > 0) {
                dynalloc_logp = Inlines.IMAX(2, dynalloc_logp - 1);
            }
        }

        fine_quant = new int[nbEBands];
        alloc_trim = tell + (6 << EntropyCoder.BITRES) <= total_bits
                ? dec.dec_icdf(CeltTables.trim_icdf, 7) : 5;

        bits = ((len * 8) << EntropyCoder.BITRES) - dec.tell_frac() - 1;
        anti_collapse_rsv = isTransient != 0 && LM >= 2 && bits >= ((LM + 2) << EntropyCoder.BITRES) ? (1 << EntropyCoder.BITRES) : 0;
        bits -= anti_collapse_rsv;

        pulses = new int[nbEBands];
        fine_priority = new int[nbEBands];

        BoxedValueInt boxed_intensity = new BoxedValueInt(intensity);
        BoxedValueInt boxed_dual_stereo = new BoxedValueInt(dual_stereo);
        BoxedValueInt boxed_balance = new BoxedValueInt(0);
        codedBands = Rate.compute_allocation(mode, 0, end, offsets, cap,
                alloc_trim, boxed_intensity, boxed_dual_stereo, bits, boxed_balance, pulses,
                fine_quant, fine_priority, C, LM, dec);
        intensity = boxed_intensity.Val;
        dual_stereo = boxed_dual_stereo.Val;
        balance = boxed_balance.Val;

        QuantizeBands.unquant_fine_energy(mode, 0, end, oldBandE, fine_quant, dec, C);

        c = 0;
        do {
            System.arraycopy(decode_mem[c], N, decode_mem[c], 0, CeltConstants.DECODE_BUFFER_SIZE - N + overlap / 2);
        } while (++c < CC);

        /* Decode fixed codebook */
        collapse_masks = new short[C * nbEBands];

        X = new int[C][N];

        BoxedValueInt boxed_rng = new BoxedValueInt(rng);
        Bands.quant_all_bands(mode, 0, end, X[0], C == 2 ? X[1] : null, collapse_masks,
                null, pulses, shortBlocks, spread_decision, dual_stereo, intensity, tf_res,
                len * (8 << EntropyCoder.BITRES) - anti_collapse_rsv, balance, dec, LM, codedBands, boxed_rng);
        this.rng = boxed_rng.Val;

        if (anti_collapse_rsv > 0) {
            anti_collapse_on = dec.dec_bits(1);
        }

        QuantizeBands.unquant_energy_finalise(mode, 0, end, oldBandE,
                fine_quant, fine_priority, len * 8 - dec.tell(), dec, C);

        if (anti_collapse_on != 0) {
            Bands.anti_collapse(mode, X, collapse_masks, LM, C,
                    0, end, oldBandE, oldLogE, oldLogE2, pulses, rng);
        }

        if (silence != 0) {
            for (i = 0; i < C * nbEBands; i++) {
                oldBandE[i] = -Inlines.QCONST16(28.0f, CeltConstants.DB_SHIFT);
            }
        }

        CeltCommon.celt_synthesis(mode, X, out_syn, out_syn_ptrs, oldBandE, 0, effEnd,
                C, CC, isTransient, LM, downsample, silence);

        c = 0;
        do {
            this.postfilter_period = Inlines.IMAX(postfilter_period, CeltConstants.COMBFILTER_MINPERIOD);
            this.postfilter_period_old = Inlines.IMAX(postfilter_period_old, CeltConstants.COMBFILTER_MINPERIOD);
            CeltCommon.comb_filter(out_syn[c], out_syn_ptrs[c], out_syn[c], out_syn_ptrs[c], postfilter_period_old, postfilter_period, mode.shortMdctSize,
                    postfilter_gain_old, this.postfilter_gain, postfilter_tapset_old, this.postfilter_tapset,
                    mode.window, overlap);
            if (LM != 0) {
                CeltCommon.comb_filter(
                        out_syn[c], out_syn_ptrs[c] + (mode.shortMdctSize),
                        out_syn[c], out_syn_ptrs[c] + (mode.shortMdctSize),
                        postfilter_period, postfilter_pitch, N - mode.shortMdctSize,
                        this.postfilter_gain, postfilter_gain, this.postfilter_tapset, postfilter_tapset,
                        mode.window, overlap);
            }

        } while (++c < CC);
        this.postfilter_period_old = postfilter_period;
        this.postfilter_gain_old = this.postfilter_gain;
        this.postfilter_tapset_old = this.postfilter_tapset;
        this.postfilter_period = postfilter_pitch;
        this.postfilter_gain = postfilter_gain;
        this.postfilter_tapset = postfilter_tapset;
        if (LM != 0) {
            this.postfilter_period_old = postfilter_period;
            this.postfilter_gain_old = this.postfilter_gain;
            this.postfilter_tapset_old = this.postfilter_tapset;
        }

        if (C == 1) {
            System.arraycopy(oldBandE, 0, oldBandE, nbEBands, nbEBands);
        }

        if (isTransient == 0) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, 2 * nbEBands);
            System.arraycopy(oldBandE, 0, oldLogE, 0, 2 * nbEBands);
        } else {
            for (i = 0; i < 2 * nbEBands; i++) {
                oldLogE[i] = Inlines.MIN16(oldLogE[i], oldBandE[i]);
            }
        }
        c = 0;
        do {
            for (i = end; i < nbEBands; i++) {
                oldBandE[c * nbEBands + i] = 0;
                oldLogE[c * nbEBands + i] = oldLogE2[c * nbEBands + i] = -Inlines.QCONST16(28.0f, CeltConstants.DB_SHIFT);
            }
        } while (++c < 2);
        this.rng = (int) dec.rng;

        CeltCommon.deemphasis(out_syn, out_syn_ptrs, pcm, pcm_ptr, N, CC, downsample, mode.preemph, preemph_memD);

        if (dec.tell() > 8 * len) {
            return OpusError.OPUS_INTERNAL_ERROR;
        }
        if (dec.get_error() != 0) {
            this.error = 1;
        }
        return frame_size / downsample;
    }

    void SetEndBand(int value) {
        if (value < 1 || value > mode.nbEBands) {
            throw new IllegalArgumentException("End band above max number of ebands (or less than 1)");
        }
        this.end = value;
    }

    void SetChannels(int value) {
        if (value < 1 || value > 2) {
            throw new IllegalArgumentException("Channel count must be 1 or 2");
        }
        this.stream_channels = value;
    }

    public int GetPitch() {
        return postfilter_period;
    }

}
