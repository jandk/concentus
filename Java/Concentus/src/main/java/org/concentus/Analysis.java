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

class Analysis {

    private static final double M_PI = 3.141592653;
    private static final float cA = 0.43157974f;
    private static final float cB = 0.67848403f;
    private static final float cC = 0.08595542f;
    private static final float cE = ((float) M_PI / 2);

    static float fast_atan2f(float y, float x) {
        float x2, y2;
        /* Should avoid underflow on the values we'll get */
        if (Inlines.ABS16(x) + Inlines.ABS16(y) < 1e-9f) {
            x *= 1e12f;
            y *= 1e12f;
        }
        x2 = x * x;
        y2 = y * y;
        if (x2 < y2) {
            float den = (y2 + cB * x2) * (y2 + cC * x2);
            if (den != 0) {
                return -x * y * (y2 + cA * x2) / den + (y < 0 ? -cE : cE);
            } else {
                return (y < 0 ? -cE : cE);
            }
        } else {
            float den = (x2 + cB * y2) * (x2 + cC * y2);
            if (den != 0) {
                return x * y * (x2 + cA * y2) / den + (y < 0 ? -cE : cE) - (x * y < 0 ? -cE : cE);
            } else {
                return (y < 0 ? -cE : cE) - (x * y < 0 ? -cE : cE);
            }
        }
    }

    static void tonality_analysis_init(TonalityAnalysisState tonal) {
        tonal.Reset();
    }

    static void tonality_get_info(TonalityAnalysisState tonal, AnalysisInfo info_out, int len) {
        int pos;

        pos = tonal.read_pos;

        if (len > 480 && pos != tonal.write_pos) {
            pos++;
            if (pos == OpusConstants.DETECT_SIZE) {
                pos = 0;
            }
        }
        if (pos == tonal.write_pos) {
            pos--;
        }
        if (pos < 0) {
            pos = OpusConstants.DETECT_SIZE - 1;
        }

        info_out.Assign(tonal.info[pos]);
        tonal.read_subframe += len / 120;
        while (tonal.read_subframe >= 4) {
            tonal.read_subframe -= 4;
            tonal.read_pos++;
        }
        if (tonal.read_pos >= OpusConstants.DETECT_SIZE) {
            tonal.read_pos -= OpusConstants.DETECT_SIZE;
        }

    }

    /// <summary>
    ///
    /// </summary>
    /// <param name="tonal"></param>
    /// <param name="celt_mode"></param>
    /// <param name="x"></param>
    /// <param name="len"></param>
    /// <param name="offset"></param>
    /// <param name="c1"></param>
    /// <param name="c2"></param>
    /// <param name="C"></param>
    /// <param name="lsb_depth"></param>
    static void tonality_analysis(TonalityAnalysisState tonal, CeltMode celt_mode, short[] x, int x_ptr, int len, int offset, int c1, int c2, int C, int lsb_depth) {
        int i, b;
        FFTState kfft;
        int[] input;
        int[] output;
        int N = 480, N2 = 240;
        float[] A = tonal.angle;
        float[] dA = tonal.d_angle;
        float[] d2A = tonal.d2_angle;
        float[] tonality;
        float[] noisiness;
        float[] band_tonality = new float[OpusConstants.NB_TBANDS];
        float[] logE = new float[OpusConstants.NB_TBANDS];
        float frame_tonality;
        float max_frame_tonality;
        /*float tw_sum=0;*/
        float frame_noisiness;
        float pi4 = (float) (M_PI * M_PI * M_PI * M_PI);
        float slope = 0;
        float relativeE;
        float alphaE2;
        float bandwidth_mask;
        int bandwidth = 0;
        float maxE = 0;
        float noise_floor;
        int remaining;
        AnalysisInfo info; //[porting note] pointer

        alphaE2 = 1.0f / Inlines.IMIN(1000, 1 + tonal.count);

        kfft = celt_mode.mdct.kfft[0];
        if (tonal.count == 0) {
            tonal.mem_fill = 240;
        }

        Downmix.downmix_int(x, x_ptr, tonal.inmem, tonal.mem_fill, Inlines.IMIN(len, OpusConstants.ANALYSIS_BUF_SIZE - tonal.mem_fill), offset, c1, c2, C);

        if (tonal.mem_fill + len < OpusConstants.ANALYSIS_BUF_SIZE) {
            tonal.mem_fill += len;
            /* Don't have enough to update the analysis */
            return;
        }

        info = tonal.info[tonal.write_pos++];
        if (tonal.write_pos >= OpusConstants.DETECT_SIZE) {
            tonal.write_pos -= OpusConstants.DETECT_SIZE;
        }

        input = new int[960];
        output = new int[960];
        tonality = new float[240];
        noisiness = new float[240];
        for (i = 0; i < N2; i++) {
            float w = OpusTables.analysis_window[i];
            input[2 * i] = (int) (w * tonal.inmem[i]);
            input[2 * i + 1] = (int) (w * tonal.inmem[N2 + i]);
            input[(2 * (N - i - 1))] = (int) (w * tonal.inmem[N - i - 1]);
            input[(2 * (N - i - 1)) + 1] = (int) (w * tonal.inmem[N + N2 - i - 1]);
        }
        System.arraycopy(tonal.inmem, OpusConstants.ANALYSIS_BUF_SIZE - 240, tonal.inmem, 0, 240);

        remaining = len - (OpusConstants.ANALYSIS_BUF_SIZE - tonal.mem_fill);
        Downmix.downmix_int(x, x_ptr, tonal.inmem, 240, remaining, offset + OpusConstants.ANALYSIS_BUF_SIZE - tonal.mem_fill, c1, c2, C);
        tonal.mem_fill = 240 + remaining;

        KissFFT.opus_fft(kfft, input, output);

        for (i = 1; i < N2; i++) {
            float X1r, X2r, X1i, X2i;
            float angle, d_angle, d2_angle;
            float angle2, d_angle2, d2_angle2;
            float mod1, mod2, avg_mod;
            X1r = (float) output[2 * i] + output[2 * (N - i)];
            X1i = (float) output[(2 * i) + 1] - output[2 * (N - i) + 1];
            X2r = (float) output[(2 * i) + 1] + output[2 * (N - i) + 1];
            X2i = (float) output[2 * (N - i)] - output[2 * i];

            angle = (float) (.5f / M_PI) * fast_atan2f(X1i, X1r);
            d_angle = angle - A[i];
            d2_angle = d_angle - dA[i];

            angle2 = (float) (.5f / M_PI) * fast_atan2f(X2i, X2r);
            d_angle2 = angle2 - angle;
            d2_angle2 = d_angle2 - d_angle;

            mod1 = d2_angle - (float) Math.floor(0.5f + d2_angle);
            noisiness[i] = Inlines.ABS16(mod1);
            mod1 *= mod1;
            mod1 *= mod1;

            mod2 = d2_angle2 - (float) Math.floor(0.5f + d2_angle2);
            noisiness[i] += Inlines.ABS16(mod2);
            mod2 *= mod2;
            mod2 *= mod2;

            avg_mod = .25f * (d2A[i] + 2.0f * mod1 + mod2);
            tonality[i] = 1.0f / (1.0f + 40.0f * 16.0f * pi4 * avg_mod) - .015f;

            A[i] = angle2;
            dA[i] = d_angle2;
            d2A[i] = mod2;
        }

        frame_tonality = 0;
        max_frame_tonality = 0;
        /*tw_sum = 0;*/
        info.activity = 0;
        frame_noisiness = 0;
        if (tonal.count == 0) {
            for (b = 0; b < OpusConstants.NB_TBANDS; b++) {
                tonal.lowE[b] = 1e10f;
                tonal.highE[b] = -1e10f;
            }
        }
        relativeE = 0;
        for (b = 0; b < OpusConstants.NB_TBANDS; b++) {
            float E = 0, tE = 0, nE = 0;
            float L1, L2;
            float stationarity;
            for (i = OpusTables.tbands[b]; i < OpusTables.tbands[b + 1]; i++) {
                float binE = output[2 * i] * (float) output[2 * i] + output[2 * (N - i)] * (float) output[2 * (N - i)]
                        + output[2 * i + 1] * (float) output[2 * i + 1] + output[2 * (N - i) + 1] * (float) output[2 * (N - i) + 1];
                /* FIXME: It's probably best to change the BFCC filter initial state instead */
                binE *= 5.55e-17f;
                E += binE;
                tE += binE * tonality[i];
                nE += binE * 2.0f * (.5f - noisiness[i]);
            }

            tonal.E[tonal.E_count][b] = E;
            frame_noisiness += nE / (1e-15f + E);

            logE[b] = (float) Math.log(E + 1e-10f);
            tonal.lowE[b] = Inlines.MIN32(logE[b], tonal.lowE[b] + 0.01f);
            tonal.highE[b] = Inlines.MAX32(logE[b], tonal.highE[b] - 0.1f);
            if (tonal.highE[b] < tonal.lowE[b] + 1.0f) {
                tonal.highE[b] += 0.5f;
                tonal.lowE[b] -= 0.5f;
            }
            relativeE += (logE[b] - tonal.lowE[b]) / (1e-15f + tonal.highE[b] - tonal.lowE[b]);

            L1 = L2 = 0;
            for (i = 0; i < OpusConstants.NB_FRAMES; i++) {
                L1 += (float) Math.sqrt(tonal.E[i][b]);
                L2 += tonal.E[i][b];
            }

            stationarity = Inlines.MIN16(0.99f, L1 / (float) Math.sqrt(1e-15 + OpusConstants.NB_FRAMES * L2));
            stationarity *= stationarity;
            stationarity *= stationarity;
            /*band_tonality[b] = tE/(1e-15+E)*/
            band_tonality[b] = Inlines.MAX16(tE / (1e-15f + E), stationarity * tonal.prev_band_tonality[b]);
            frame_tonality += band_tonality[b];
            if (b >= OpusConstants.NB_TBANDS - OpusConstants.NB_TONAL_SKIP_BANDS) {
                frame_tonality -= band_tonality[b - OpusConstants.NB_TBANDS + OpusConstants.NB_TONAL_SKIP_BANDS];
            }
            max_frame_tonality = Inlines.MAX16(max_frame_tonality, (1.0f + .03f * (b - OpusConstants.NB_TBANDS)) * frame_tonality);
            slope += band_tonality[b] * (b - 8);
            tonal.prev_band_tonality[b] = band_tonality[b];
        }

        bandwidth_mask = 0;
        bandwidth = 0;
        maxE = 0;
        noise_floor = 5.7e-4f / (1 << (Inlines.IMAX(0, lsb_depth - 8)));
        noise_floor *= 1 << (15 + CeltConstants.SIG_SHIFT);
        noise_floor *= noise_floor;
        for (b = 0; b < OpusConstants.NB_TOT_BANDS; b++) {
            float E = 0;
            int band_start, band_end;
            /* Keep a margin of 300 Hz for aliasing */
            band_start = OpusTables.extra_bands[b];
            band_end = OpusTables.extra_bands[b + 1];
            for (i = band_start; i < band_end; i++) {
                float binE = output[2 * i] * (float) output[2 * i] + output[2 * (N - i)] * (float) output[2 * (N - i)]
                        + output[2 * i + 1] * (float) output[2 * i + 1] + output[2 * (N - i) + 1] * (float) output[2 * (N - i) + 1];
                E += binE;
            }
            maxE = Inlines.MAX32(maxE, E);
            tonal.meanE[b] = Inlines.MAX32((1 - alphaE2) * tonal.meanE[b], E);
            E = Inlines.MAX32(E, tonal.meanE[b]);
            /* Use a simple follower with 13 dB/Bark slope for spreading function */
            bandwidth_mask = Inlines.MAX32(.05f * bandwidth_mask, E);
            /* Consider the band "active" only if all these conditions are met:
               1) less than 10 dB below the simple follower
               2) less than 90 dB below the peak band (maximal masking possible considering
                  both the ATH and the loudness-dependent slope of the spreading function)
               3) above the PCM quantization noise floor
             */
            if (E > .1 * bandwidth_mask && E * 1e9f > maxE && E > noise_floor * (band_end - band_start)) {
                bandwidth = b;
            }
        }
        if (tonal.count <= 2) {
            bandwidth = 20;
        }
        relativeE /= OpusConstants.NB_TBANDS;
        if (tonal.count < 10) {
            relativeE = 0.5f;
        }
        frame_noisiness /= OpusConstants.NB_TBANDS;
        info.activity = frame_noisiness + (1 - frame_noisiness) * relativeE;
        frame_tonality = (max_frame_tonality / (OpusConstants.NB_TBANDS - OpusConstants.NB_TONAL_SKIP_BANDS));
        frame_tonality = Inlines.MAX16(frame_tonality, tonal.prev_tonality * .8f);
        tonal.prev_tonality = frame_tonality;

        slope /= 8 * 8;
        info.tonality_slope = slope;

        tonal.E_count = (tonal.E_count + 1) % OpusConstants.NB_FRAMES;
        tonal.count++;
        info.tonality = frame_tonality;

        info.bandwidth = bandwidth;
        info.noisiness = frame_noisiness;
        info.valid = 1;
    }

    static void run_analysis(TonalityAnalysisState analysis, CeltMode celt_mode, short[] analysis_pcm, int analysis_pcm_ptr,
                             int analysis_frame_size, int frame_size, int c1, int c2, int C, int Fs,
                             int lsb_depth, AnalysisInfo analysis_info) {
        int offset;
        int pcm_len;

        if (analysis_pcm != null) {
            /* Avoid overflow/wrap-around of the analysis buffer */
            analysis_frame_size = Inlines.IMIN((OpusConstants.DETECT_SIZE - 5) * Fs / 100, analysis_frame_size);

            pcm_len = analysis_frame_size - analysis.analysis_offset;
            offset = analysis.analysis_offset;
            do {
                tonality_analysis(analysis, celt_mode, analysis_pcm, analysis_pcm_ptr, Inlines.IMIN(480, pcm_len), offset, c1, c2, C, lsb_depth);
                offset += 480;
                pcm_len -= 480;
            } while (pcm_len > 0);
            analysis.analysis_offset = analysis_frame_size;

            analysis.analysis_offset -= frame_size;
        }

        analysis_info.valid = 0;
        tonality_get_info(analysis, analysis_info, frame_size);
    }
}
