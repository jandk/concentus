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

class CeltCommon {

    static void deemphasis(int[][] input, int[] input_ptrs, short[] pcm, int pcm_ptr, int N, int C, int downsample, int[] coef,
                           int[] mem) {
        int c;
        int Nd;
        int apply_downsampling = 0;
        int coef0;
        int[] scratch = new int[N];
        coef0 = coef[0];
        Nd = N / downsample;
        c = 0;
        do {
            int j;
            int x_ptr;
            int y;
            int m = mem[c];
            int[] x = input[c];
            x_ptr = input_ptrs[c];
            y = pcm_ptr + c;
            if (downsample > 1) {
                /* Shortcut for the standard (non-custom modes) case */
                for (j = 0; j < N; j++) {
                    int tmp = x[x_ptr + j] + m + CeltConstants.VERY_SMALL;
                    m = Inlines.MULT16_32_Q15(coef0, tmp);
                    scratch[j] = tmp;
                }
                apply_downsampling = 1;
            } else {
                for (j = 0; j < N; j++) {
                    int tmp = (x[x_ptr + j] + m + CeltConstants.VERY_SMALL); // Opus bug: This can overflow.
                    if (x[x_ptr + j] > 0 && m > 0 && tmp < 0) // This is a hack to saturate to INT_MAXVALUE
                    {
                        tmp = Integer.MAX_VALUE;
                        m = Integer.MAX_VALUE;
                    } else {
                        m = Inlines.MULT16_32_Q15(coef0, tmp);
                    }
                    pcm[y + (j * C)] = Inlines.SIG2WORD16(tmp);
                }
            }
            mem[c] = m;

            if (apply_downsampling != 0) {
                /* Perform down-sampling */
                {
                    for (j = 0; j < Nd; j++) {
                        pcm[y + (j * C)] = Inlines.SIG2WORD16(scratch[j * downsample]);
                    }
                }
            }
        } while (++c < C);

    }

    static void celt_synthesis(CeltMode mode, int[][] X, int[][] out_syn, int[] out_syn_ptrs,
                               int[] oldBandE, int start, int effEnd, int C, int CC,
                               int isTransient, int LM, int downsample,
                               int silence) {
        int c, i;
        int M;
        int b;
        int B;
        int N, NB;
        int shift;
        int nbEBands;
        int overlap;
        int[] freq;

        overlap = mode.overlap;
        nbEBands = mode.nbEBands;
        N = mode.shortMdctSize << LM;
        freq = new int[N];
        M = 1 << LM;

        if (isTransient != 0) {
            B = M;
            NB = mode.shortMdctSize;
            shift = mode.maxLM;
        } else {
            B = 1;
            NB = mode.shortMdctSize << LM;
            shift = mode.maxLM - LM;
        }

        if (CC == 2 && C == 1) {
            /* Copying a mono streams to two channels */
            int freq2;
            Bands.denormalise_bands(mode, X[0], freq, 0, oldBandE, 0, start, effEnd, M,
                    downsample, silence);
            /* Store a temporary copy in the output buffer because the IMDCT destroys its input. */
            freq2 = out_syn_ptrs[1] + (overlap / 2);
            System.arraycopy(freq, 0, out_syn[1], freq2, N);
            for (b = 0; b < B; b++) {
                MDCT.clt_mdct_backward(mode.mdct, out_syn[1], freq2 + b, out_syn[0], out_syn_ptrs[0] + (NB * b), mode.window, overlap, shift, B);
            }
            for (b = 0; b < B; b++) {
                MDCT.clt_mdct_backward(mode.mdct, freq, b, out_syn[1], out_syn_ptrs[1] + (NB * b), mode.window, overlap, shift, B);
            }
        } else if (CC == 1 && C == 2) {
            /* Downmixing a stereo stream to mono */
            int freq2 = out_syn_ptrs[0] + (overlap / 2);
            Bands.denormalise_bands(mode, X[0], freq, 0, oldBandE, 0, start, effEnd, M,
                    downsample, silence);
            /* Use the output buffer as temp array before downmixing. */
            Bands.denormalise_bands(mode, X[1], out_syn[0], freq2, oldBandE, nbEBands, start, effEnd, M,
                    downsample, silence);
            for (i = 0; i < N; i++) {
                freq[i] = Inlines.HALF32(Inlines.ADD32(freq[i], out_syn[0][freq2 + i]));
            }
            for (b = 0; b < B; b++) {
                MDCT.clt_mdct_backward(mode.mdct, freq, b, out_syn[0], out_syn_ptrs[0] + (NB * b), mode.window, overlap, shift, B);
            }
        } else {
            /* Normal case (mono or stereo) */
            c = 0;
            do {
                Bands.denormalise_bands(mode, X[c], freq, 0, oldBandE, c * nbEBands, start, effEnd, M,
                        downsample, silence);
                for (b = 0; b < B; b++) {
                    MDCT.clt_mdct_backward(mode.mdct, freq, b, out_syn[c], out_syn_ptrs[c] + (NB * b), mode.window, overlap, shift, B);
                }
            } while (++c < CC);
        }

    }

    static void tf_decode(int start, int end, int isTransient, int[] tf_res, int LM, EntropyCoder dec) {
        int i, curr, tf_select;
        int tf_select_rsv;
        int tf_changed;
        int logp;
        int budget;
        int tell;

        budget = dec.storage * 8;
        tell = dec.tell();
        logp = isTransient != 0 ? 2 : 4;
        tf_select_rsv = (LM > 0 && tell + logp + 1 <= budget) ? 1 : 0;
        budget -= tf_select_rsv;
        tf_changed = curr = 0;
        for (i = start; i < end; i++) {
            if (tell + logp <= budget) {
                curr ^= dec.dec_bit_logp(logp);
                tell = dec.tell();
                tf_changed |= curr;
            }
            tf_res[i] = curr;
            logp = isTransient != 0 ? 4 : 5;
        }
        tf_select = 0;
        if (tf_select_rsv != 0
                && CeltTables.tf_select_table[LM][4 * isTransient + tf_changed]
                != CeltTables.tf_select_table[LM][4 * isTransient + 2 + tf_changed]) {
            tf_select = dec.dec_bit_logp(1);
        }
        for (i = start; i < end; i++) {
            tf_res[i] = CeltTables.tf_select_table[LM][4 * isTransient + 2 * tf_select + tf_res[i]];
        }
    }

    static int resampling_factor(int rate) {
        int ret;
        switch (rate) {
            case 48000:
                ret = 1;
                break;
            case 24000:
                ret = 2;
                break;
            case 16000:
                ret = 3;
                break;
            case 12000:
                ret = 4;
                break;
            case 8000:
                ret = 6;
                break;
            default:
                Inlines.OpusAssert(false);
                ret = 0;
                break;
        }
        return ret;
    }

    static void comb_filter_const(int[] y, int y_ptr, int[] x, int x_ptr, int T, int N,
                                  int g10, int g11, int g12) {
        int x0, x1, x2, x3, x4;
        int i;
        int xpt = x_ptr - T;
        x4 = x[xpt - 2];
        x3 = x[xpt - 1];
        x2 = x[xpt];
        x1 = x[xpt + 1];
        for (i = 0; i < N; i++) {
            x0 = x[xpt + i + 2];
            y[y_ptr + i] = x[x_ptr + i]
                    + Inlines.MULT16_32_Q15(g10, x2)
                    + Inlines.MULT16_32_Q15(g11, Inlines.ADD32(x1, x3))
                    + Inlines.MULT16_32_Q15(g12, Inlines.ADD32(x0, x4));
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    private static final short[][] gains = {
            new short[]{((short) (0.5 + (0.3066406250f) * (1 << (15))))/*Inlines.QCONST16(0.3066406250f, 15)*/, ((short) (0.5 + (0.2170410156f) * (1 << (15))))/*Inlines.QCONST16(0.2170410156f, 15)*/, ((short) (0.5 + (0.1296386719f) * (1 << (15))))/*Inlines.QCONST16(0.1296386719f, 15)*/},
            new short[]{((short) (0.5 + (0.4638671875f) * (1 << (15))))/*Inlines.QCONST16(0.4638671875f, 15)*/, ((short) (0.5 + (0.2680664062f) * (1 << (15))))/*Inlines.QCONST16(0.2680664062f, 15)*/, ((short) (0.5 + (0.0f) * (1 << (15))))/*Inlines.QCONST16(0.0f, 15)*/},
            new short[]{((short) (0.5 + (0.7998046875f) * (1 << (15))))/*Inlines.QCONST16(0.7998046875f, 15)*/, ((short) (0.5 + (0.1000976562f) * (1 << (15))))/*Inlines.QCONST16(0.1000976562f, 15)*/, ((short) (0.5 + (0.0f) * (1 << (15))))/*Inlines.QCONST16(0.0f, 15)*/}
    };

    static void comb_filter(int[] y, int y_ptr, int[] x, int x_ptr, int T0, int T1, int N,
                            int g0, int g1, int tapset0, int tapset1,
                            int[] window, int overlap) {
        int i;
        /* printf ("%d %d %f %f\n", T0, T1, g0, g1); */
        int g00, g01, g02, g10, g11, g12;
        int x0, x1, x2, x3, x4;

        if (g0 == 0 && g1 == 0) {
            /* OPT: Happens to work without the OPUS_MOVE(), but only because the current encoder already copies x to y */
            if (x_ptr != y_ptr) {
                //x.MemMoveTo(y, N);
            }

            return;
        }
        g00 = Inlines.MULT16_16_P15(g0, gains[tapset0][0]);
        g01 = Inlines.MULT16_16_P15(g0, gains[tapset0][1]);
        g02 = Inlines.MULT16_16_P15(g0, gains[tapset0][2]);
        g10 = Inlines.MULT16_16_P15(g1, gains[tapset1][0]);
        g11 = Inlines.MULT16_16_P15(g1, gains[tapset1][1]);
        g12 = Inlines.MULT16_16_P15(g1, gains[tapset1][2]);
        x1 = x[x_ptr - T1 + 1];
        x2 = x[x_ptr - T1];
        x3 = x[x_ptr - T1 - 1];
        x4 = x[x_ptr - T1 - 2];
        /* If the filter didn't change, we don't need the overlap */
        if (g0 == g1 && T0 == T1 && tapset0 == tapset1) {
            overlap = 0;
        }
        for (i = 0; i < overlap; i++) {
            int f;
            x0 = x[x_ptr + i - T1 + 2];
            f = Inlines.MULT16_16_Q15(window[i], window[i]);
            y[y_ptr + i] = x[x_ptr + i]
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15((short) (CeltConstants.Q15ONE - f), g00), x[x_ptr + i - T0])
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15((short) (CeltConstants.Q15ONE - f), g01), Inlines.ADD32(x[x_ptr + i - T0 + 1], x[x_ptr + i - T0 - 1]))
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15((short) (CeltConstants.Q15ONE - f), g02), Inlines.ADD32(x[x_ptr + i - T0 + 2], x[x_ptr + i - T0 - 2]))
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15(f, g10), x2)
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15(f, g11), Inlines.ADD32(x1, x3))
                    + Inlines.MULT16_32_Q15(Inlines.MULT16_16_Q15(f, g12), Inlines.ADD32(x0, x4));
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;

        }
        if (g1 == 0) {
            /* OPT: Happens to work without the OPUS_MOVE(), but only because the current encoder already copies x to y */
            if (x_ptr != y_ptr) {
                //x.Point(overlap).MemMoveTo(y.Point(overlap), N - overlap);
            }
            return;
        }

        /* Compute the part with the constant filter. */
        comb_filter_const(y, y_ptr + i, x, x_ptr + i, T1, N - i, g10, g11, g12);
    }

    static void init_caps(CeltMode m, int[] cap, int LM, int C) {
        int i;
        for (i = 0; i < m.nbEBands; i++) {
            int N;
            N = (m.eBands[i + 1] - m.eBands[i]) << LM;
            cap[i] = (m.cache.caps[m.nbEBands * (2 * LM + C - 1) + i] + 64) * C * N >> 2;
        }
    }
}
