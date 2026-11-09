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

final class Bands {

    static int celt_lcg_rand(int seed) {
        return (1664525 * seed + 1013904223);
    }

    /* This is a cos() approximation designed to be bit-exact on any platform. Bit exactness
       with this approximation is important because it has an impact on the bit allocation */
    static int bitexact_cos(int x) {
        int tmp = (4096 + (x * (x))) >> 13;
        Inlines.OpusAssert(tmp <= 32767);
        int x2 = (tmp);
        x2 = ((32767 - x2) + Inlines.FRAC_MUL16(x2, (-7651 + Inlines.FRAC_MUL16(x2, (8277 + Inlines.FRAC_MUL16(-626, x2))))));
        Inlines.OpusAssert(x2 <= 32766);
        return (1 + x2);
    }

    static int bitexact_log2tan(int isin, int icos) {
        int lc = Inlines.EC_ILOG(icos);
        int ls = Inlines.EC_ILOG(isin);
        icos <<= 15 - lc;
        isin <<= 15 - ls;
        return (ls - lc) * (1 << 11)
                + Inlines.FRAC_MUL16(isin, Inlines.FRAC_MUL16(isin, -2597) + 7932)
                - Inlines.FRAC_MUL16(icos, Inlines.FRAC_MUL16(icos, -2597) + 7932);
    }

    /* De-normalise the energy to produce the synthesis from the unit-energy bands */
    static void denormalise_bands(CeltMode m, float[] X,
                                  float[] freq, int freq_ptr, float[] bandLogE, int bandLogE_ptr, int start,
                                  int end, int M, int downsample, int silence) {
        short[] eBands = m.eBands;
        int N = M * m.shortMdctSize;
        int bound = M * eBands[end];
        if (downsample != 1) {
            bound = Math.min(bound, N / downsample);
        }
        if (silence != 0) {
            bound = 0;
            start = end = 0;
        }
        int f = freq_ptr;
        int x = M * eBands[start];

        int i;
        for (i = 0; i < M * eBands[start]; i++) {
            freq[f++] = 0;
        }

        for (i = start; i < end; i++) {

            int j = M * eBands[i];
            int band_end = M * eBands[i + 1];
            float lg = bandLogE[bandLogE_ptr + i] + CeltTablesF.eMeans[i];
            float g = InlinesF.celt_exp2(Math.min(32.f, lg));

            do {
                freq[f++] = X[x++] * g;
            } while (++j < band_end);
        }

        Inlines.OpusAssert(start <= end);
        Arrays.fill(freq, freq_ptr + bound, freq_ptr + bound + N - bound, 0);
    }

    /* This prevents energy collapse for transients with multiple short MDCTs */
    static void anti_collapse(CeltMode m, float[][] X_, short[] collapse_masks, int LM, int C,
                              int start, int end, float[] logE, float[] prev1logE,
                              float[] prev2logE, int[] pulses, int seed) {
        for (int i = start; i < end; i++) {
            int N0 = m.eBands[i + 1] - m.eBands[i];
            /* depth in 1/8 bits */
            Inlines.OpusAssert(pulses[i] >= 0);
            int depth = ((pulses[i] + 1) / N0) >> LM;

            float thresh = 0.5f * InlinesF.celt_exp2(-0.125f * depth);
            float sqrt_1 = InlinesF.celt_rsqrt(N0 << LM);

            int c = 0;
            do {
                float prev1 = prev1logE[c * m.nbEBands + i];
                float prev2 = prev2logE[c * m.nbEBands + i];
                if (C == 1) {
                    prev1 = Math.max(prev1, prev1logE[m.nbEBands + i]);
                    prev2 = Math.max(prev2, prev2logE[m.nbEBands + i]);
                }
                float Ediff = logE[c * m.nbEBands + i] - Math.min(prev1, prev2);
                Ediff = Math.max(0, Ediff);

                float r = 2.f * InlinesF.celt_exp2(-Ediff);
                if (LM == 3) {
                    r *= 1.41421356f;
                }
                r = Math.min(thresh, r) * sqrt_1;

                int X = m.eBands[i] << LM;
                int renormalize = 0;
                for (int k = 0; k < 1 << LM; k++) {
                    /* Detect collapse */
                    if ((collapse_masks[i * C + c] & 1 << k) == 0) {
                        /* Fill with noise */
                        int Xk = X + k;
                        for (int j = 0; j < N0; j++) {
                            seed = celt_lcg_rand(seed);
                            X_[c][Xk + (j << LM)] = ((seed & 0x8000) != 0 ? r : -r);
                        }
                        renormalize = 1;
                    }
                }
                /* We just added some energy, so we need to renormalise */
                if (renormalize != 0) {
                    VQ.renormalise_vector(X_[c], X, N0 << LM, 1.0f);
                }
            } while (++c < C);
        }
    }

    static void stereo_merge(float[] X, int X_ptr, float[] Y, int Y_ptr, float mid, int N) {
        BoxedValueFloat xp = new BoxedValueFloat(0);
        BoxedValueFloat side = new BoxedValueFloat(0);

        /* Compute the norm of X+Y and X-Y as |X|^2 + |Y|^2 +/- sum(xy) */
        Kernels.dual_inner_prod(Y, Y_ptr, X, X_ptr, Y, Y_ptr, N, xp, side);

        /* Compensating for the mid normalization */
        xp.Val *= mid;
        float El = mid * mid + side.Val - 2 * xp.Val;
        float Er = mid * mid + side.Val + 2 * xp.Val;
        if (Er < 6e-4f || El < 6e-4f) {
            System.arraycopy(X, X_ptr, Y, Y_ptr, N);
            return;
        }

        float lgain = InlinesF.celt_rsqrt(El);
        float rgain = InlinesF.celt_rsqrt(Er);
        for (int i = 0; i < N; i++) {
            float l = X[X_ptr + i] * mid;
            float r = Y[Y_ptr + i];
            X[X_ptr + i] = lgain * (l - r);
            Y[Y_ptr + i] = rgain * (l + r);
        }
    }

    static void deinterleave_hadamard(float[] X, int X_ptr, int N0, int stride, int hadamard) {
        int N = N0 * stride;

        Inlines.OpusAssert(stride > 0);
        float[] tmp = new float[N];
        if (hadamard != 0) {
            int ordery = (stride - 2);
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < N0; j++) {
                    tmp[CeltTables.ordery_table[ordery + i] * N0 + j] = X[j * stride + i + X_ptr];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < N0; j++) {
                    tmp[i * N0 + j] = X[j * stride + i + X_ptr];
                }
            }
        }

        System.arraycopy(tmp, 0, X, X_ptr, N);
    }

    static void interleave_hadamard(float[] X, int X_ptr, int N0, int stride, int hadamard) {
        int N = N0 * stride;
        float[] tmp = new float[N];

        if (hadamard != 0) {
            int ordery = (stride - 2);
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < N0; j++) {
                    tmp[j * stride + i] = X[CeltTables.ordery_table[ordery + i] * N0 + j + X_ptr];
                }
            }
        } else {
            for (int i = 0; i < stride; i++) {
                for (int j = 0; j < N0; j++) {
                    tmp[j * stride + i] = X[i * N0 + j + X_ptr];
                }
            }
        }

        System.arraycopy(tmp, 0, X, X_ptr, N);
    }

    static void haar1(float[] X, int X_ptr, int N0, int stride) {
        N0 >>= 1;
        for (int i = 0; i < stride; i++) {
            for (int j = 0; j < N0; j++) {
                int tmpidx = X_ptr + i + (stride * 2 * j);
                float tmp1 = .70710678f * X[tmpidx];
                float tmp2 = .70710678f * X[tmpidx + stride];
                X[tmpidx] = tmp1 + tmp2;
                X[tmpidx + stride] = tmp1 - tmp2;
            }
        }
    }

    static int compute_qn(int N, int b, int offset, int pulse_cap, int stereo) {
        int N2 = 2 * N - 1;
        if (stereo != 0 && N == 2) {
            N2--;
        }

        /* The upper limit ensures that in a stereo split with itheta==16384, we'll
            always have enough bits left over to code at least one pulse in the
            side; otherwise it would collapse, since it doesn't get folded. */
        int qb = Inlines.celt_sudiv(b + N2 * offset, N2);
        qb = Math.min(b - pulse_cap - (4 << EntropyCoder.BITRES), qb);

        qb = Math.min(8 << EntropyCoder.BITRES, qb);

        int qn;
        if (qb < (1 << EntropyCoder.BITRES >> 1)) {
            qn = 1;
        } else {
            short[] exp2_table8 = {16384, 17866, 19483, 21247, 23170, 25267, 27554, 30048};
            qn = exp2_table8[qb & 0x7] >> (14 - (qb >> EntropyCoder.BITRES));
            qn = (qn + 1) >> 1 << 1;
        }
        Inlines.OpusAssert(qn <= 256);
        return qn;
    }

    public static final class band_ctx {

        public CeltMode m;
        public int i;
        public int intensity;
        public int spread;
        public int tf_change;
        public EntropyCoder ec;
        public int remaining_bits;
        public int seed;
    }

    public static final class split_ctx {

        public int inv;
        public int imid;
        public int iside;
        public int delta;
        public int itheta;
        public int qalloc;
    }

    static void compute_theta(band_ctx ctx, split_ctx sctx,
                              int N, BoxedValueInt b, int B, int B0,
                              int LM,
                              int stereo, BoxedValueInt fill) {

        CeltMode m = ctx.m;
        int i = ctx.i;
        int intensity = ctx.intensity;
        // porting note: pointer
        EntropyCoder ec = ctx.ec;

        /* Decide on the resolution to give to the split parameter theta */
        int pulse_cap = m.logN[i] + LM * (1 << EntropyCoder.BITRES);
        int offset = (pulse_cap >> 1) - (stereo != 0 && N == 2 ? CeltConstants.QTHETA_OFFSET_TWOPHASE : CeltConstants.QTHETA_OFFSET);
        int qn = compute_qn(N, b.Val, offset, pulse_cap, stereo);
        if (stereo != 0 && i >= intensity) {
            qn = 1;
        }

        int tell = ec.tell_frac();

        int inv = 0;
        int itheta = 0;
        if (qn != 1) {

            /* Entropy coding of the angle. We use a uniform pdf for the
               time split, a step for stereo, and a triangular one for the rest. */
            if (stereo != 0 && N > 2) {
                int p0 = 3;
                int x;
                int x0 = qn / 2;
                int ft = p0 * (x0 + 1) + x0;
                /* Use a probability of p0 up to itheta=8192 and then use 1 after */
                int fs = ec.decode(ft);
                if (fs < (x0 + 1) * p0) {
                    x = fs / p0;
                } else {
                    x = x0 + 1 + (fs - (x0 + 1) * p0);
                }

                ec.dec_update(
                        (x <= x0
                                ? p0 * x
                                : (x - 1 - x0) + (x0 + 1) * p0),
                        (x <= x0
                                ? p0 * (x + 1)
                                : (x - x0) + (x0 + 1) * p0),
                        ft);
                itheta = x;
            } else if (B0 > 1 || stereo != 0) {
                /* Uniform pdf */
                itheta = ec.dec_uint(qn + 1);
            } else {
                int fs;
                int ft = ((qn >> 1) + 1) * ((qn >> 1) + 1);
                /* Triangular pdf */
                int fl;
                int fm = ec.decode(ft);

                if (fm < ((qn >> 1) * ((qn >> 1) + 1) >> 1)) {
                    itheta = (Inlines.isqrt32(8 * fm + 1) - 1) >> 1;
                    fs = itheta + 1;
                    fl = itheta * (itheta + 1) >> 1;
                } else {
                    itheta = (2 * (qn + 1) - Inlines.isqrt32(8 * (ft - fm - 1) + 1)) >> 1;
                    fs = qn + 1 - itheta;
                    fl = ft - ((qn + 1 - itheta) * (qn + 2 - itheta) >> 1);
                }

                ec.dec_update(fl, (fl + fs), ft);
            }
            Inlines.OpusAssert(itheta >= 0);
            itheta = Inlines.celt_udiv(itheta * 16384, qn);
        } else if (stereo != 0) {
            if (b.Val > 2 << EntropyCoder.BITRES && ctx.remaining_bits > 2 << EntropyCoder.BITRES) {
                inv = ec.dec_bit_logp(2);
            } else {
                inv = 0;
            }
            itheta = 0;
        }
        int qalloc = ec.tell_frac() - tell;
        b.Val -= qalloc;

        int iside;
        int imid;
        int delta;
        if (itheta == 0) {
            imid = 32767;
            iside = 0;
            fill.Val &= (1 << B) - 1;
            delta = -16384;
        } else if (itheta == 16384) {
            imid = 0;
            iside = 32767;
            fill.Val &= ((1 << B) - 1) << B;
            delta = 16384;
        } else {
            imid = bitexact_cos((short) itheta);
            iside = bitexact_cos((short) (16384 - itheta));
            /* This is the mid vs side allocation that minimizes squared error
               in that band. */
            delta = Inlines.FRAC_MUL16((N - 1) << 7, bitexact_log2tan(iside, imid));
        }

        sctx.inv = inv;
        sctx.imid = imid;
        sctx.iside = iside;
        sctx.delta = delta;
        sctx.itheta = itheta;
        sctx.qalloc = qalloc;
    }

    static int quant_band_n1(band_ctx ctx, float[] X, int X_ptr, float[] Y, int Y_ptr,
                             float[] lowband_out, int lowband_out_ptr) {
        X[X_ptr] = decodeSign(ctx, ctx.ec);
        if (Y != null) {
            Y[Y_ptr] = decodeSign(ctx, ctx.ec);
        }
        if (lowband_out != null) {
            lowband_out[lowband_out_ptr] = X[X_ptr];
        }
        return 1;
    }

    private static float decodeSign(band_ctx ctx, EntropyCoder ec) {
        if (ctx.remaining_bits >= 1 << EntropyCoder.BITRES) {
            ctx.remaining_bits -= 1 << EntropyCoder.BITRES;
            return ec.dec_bits(1) != 0 ? -1.0f : 1.0f;
        }
        return 1.0f;
    }

    /* This function is responsible for encoding and decoding a mono partition.
       It can split the band in two and transmit the energy difference with
       the two half-bands. It can be called recursively so bands can end up being
       split in 8 parts. */
    static int quant_partition(band_ctx ctx, float[] X, int X_ptr,
                               int N, int b, int B, float[] lowband, int lowband_ptr,
                               int LM,
                               float gain, int fill) {
        int B0 = B;
        int cm = 0;

        //porting note: pointer
        CeltMode m = ctx.m;
        int i = ctx.i;
        int spread = ctx.spread;
        //porting note: pointer
        EntropyCoder ec = ctx.ec;
        short[] cache = m.cache.bits;
        /* If we need 1.5 more bits than we can produce, split the band in two. */
        int cache_ptr = m.cache.index[(LM + 1) * m.nbEBands + i];
        if (LM != -1 && b > cache[cache_ptr + cache[cache_ptr]] + 12 && N > 2) {
            split_ctx sctx = new split_ctx();

            N >>= 1;
            int Y = X_ptr + N;
            LM -= 1;
            if (B == 1) {
                fill = (fill & 1) | (fill << 1);
            }

            B = (B + 1) >> 1;

            BoxedValueInt boxed_b = new BoxedValueInt(b);
            BoxedValueInt boxed_fill = new BoxedValueInt(fill);
            compute_theta(ctx, sctx, N, boxed_b, B, B0, LM, 0, boxed_fill);
            b = boxed_b.Val;
            fill = boxed_fill.Val;

            float imid = sctx.imid * (1.0f / 32768.0f);
            float iside = sctx.iside * (1.0f / 32768.0f);
            int delta = sctx.delta;
            int itheta = sctx.itheta;
            int qalloc = sctx.qalloc;
            float mid = (imid);
            float side = (iside);

            /* Give more bits to low-energy MDCTs than they would otherwise deserve */
            if (B0 > 1 && ((itheta & 0x3fff) != 0)) {
                if (itheta > 8192) /* Rough approximation for pre-echo masking */ {
                    delta -= delta >> (4 - LM);
                } else /* Corresponds to a forward-masking slope of 1.5 dB per 10 ms */ {
                    delta = Math.min(0, delta + (N << EntropyCoder.BITRES >> (5 - LM)));
                }
            }
            int mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            int sbits = b - mbits;
            ctx.remaining_bits -= qalloc;

            int next_lowband2 = 0;
            if (lowband != null) {
                next_lowband2 = (lowband_ptr + N);
                /* >32-bit split case */
            }

            int rebalance = ctx.remaining_bits;
            if (mbits >= sbits) {
                cm = quant_partition(ctx, X, X_ptr, N, mbits, B,
                        lowband, lowband_ptr, LM,
                        gain * mid, fill);
                rebalance = mbits - (rebalance - ctx.remaining_bits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 0) {
                    sbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quant_partition(ctx, X, Y, N, sbits, B,
                        lowband, next_lowband2, LM,
                        gain * side, fill >> B) << (B0 >> 1);
            } else {
                cm = quant_partition(ctx, X, Y, N, sbits, B,
                        lowband, next_lowband2, LM,
                        gain * side, fill >> B) << (B0 >> 1);
                rebalance = sbits - (rebalance - ctx.remaining_bits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                cm |= quant_partition(ctx, X, X_ptr, N, mbits, B,
                        lowband, lowband_ptr, LM,
                        gain * mid, fill);
            }
        } else {
            /* This is the basic no-split case */
            int q = Rate.bits2pulses(m, i, LM, b);
            int curr_bits = Rate.pulses2bits(m, i, LM, q);
            ctx.remaining_bits -= curr_bits;

            /* Ensures we can never bust the budget */
            while (ctx.remaining_bits < 0 && q > 0) {
                ctx.remaining_bits += curr_bits;
                q--;
                curr_bits = Rate.pulses2bits(m, i, LM, q);
                ctx.remaining_bits -= curr_bits;
            }

            if (q != 0) {
                int K = Rate.get_pulses(q);

                /* Finally do the actual quantization */
                cm = VQ.alg_unquant(X, X_ptr, N, K, spread, B, ec, gain);
            } else {
                /* If there's no pulse, fill the band anyway */

                int cm_mask = (1 << B) - 1;
                fill = fill & cm_mask;

                if (fill == 0) {
                    Arrays.fill(X, X_ptr, X_ptr + N, 0);
                } else {
                    int j;
                    if (lowband == null) {
                        /* Noise */
                        for (j = 0; j < N; j++) {
                            ctx.seed = celt_lcg_rand(ctx.seed);
                            X[X_ptr + j] = (float) (ctx.seed >> 20);
                        }
                        cm = cm_mask;
                    } else {
                        /* Folded spectrum */
                        for (j = 0; j < N; j++) {
                            ctx.seed = celt_lcg_rand(ctx.seed);
                            /* About 48 dB below the "normal" folding level */
                            float tmp = 1.0f / 256;
                            tmp = (ctx.seed & 0x8000) != 0 ? tmp : -tmp;
                            X[X_ptr + j] = lowband[lowband_ptr + j] + tmp;
                        }
                        cm = fill;
                    }

                    VQ.renormalise_vector(X, X_ptr, N, gain);
                }
            }
        }

        return cm;
    }

    private static final byte[] bit_interleave_table = {0, 1, 1, 1, 2, 3, 3, 3, 2, 3, 3, 3, 2, 3, 3, 3};

    private static final short[] bit_deinterleave_table = {
            0x00, 0x03, 0x0C, 0x0F, 0x30, 0x33, 0x3C, 0x3F,
            0xC0, 0xC3, 0xCC, 0xCF, 0xF0, 0xF3, 0xFC, 0xFF
    };

    /* This function is responsible for encoding and decoding a band for the mono case. */
    static int quant_band(band_ctx ctx, float[] X, int X_ptr,
                          int N, int b, int B, float[] lowband, int lowband_ptr,
                          int LM, float[] lowband_out, int lowband_out_ptr,
                          float gain, float[] lowband_scratch, int lowband_scratch_ptr, int fill) {
        int N_B = N;
        int B0 = B;
        int tf_change = ctx.tf_change;

        int longBlocks = B0 == 1 ? 1 : 0;

        N_B /= B;

        /* Special case for one sample */
        if (N == 1) {
            return quant_band_n1(ctx, X, X_ptr, null, 0, lowband_out, lowband_out_ptr);
        }

        int recombine = 0;
        if (tf_change > 0) {
            recombine = tf_change;
        }
        /* Band recombining to increase frequency resolution */

        if (lowband_scratch != null && lowband != null && (recombine != 0 || ((N_B & 1) == 0 && tf_change < 0) || B0 > 1)) {
            System.arraycopy(lowband, lowband_ptr, lowband_scratch, lowband_scratch_ptr, N);
            lowband = lowband_scratch;
            lowband_ptr = lowband_scratch_ptr;
        }

        int k;
        for (k = 0; k < recombine; k++) {
            if (lowband != null) {
                haar1(lowband, lowband_ptr, N >> k, 1 << k);
            }
            fill = bit_interleave_table[fill & 0xF] | bit_interleave_table[fill >> 4] << 2;
        }
        B >>= recombine;
        N_B <<= recombine;

        /* Increasing the time resolution */
        int time_divide = 0;
        while ((N_B & 1) == 0 && tf_change < 0) {
            if (lowband != null) {
                haar1(lowband, lowband_ptr, N_B, B);
            }
            fill |= fill << B;
            B <<= 1;
            N_B >>= 1;
            time_divide++;
            tf_change++;
        }
        B0 = B;
        int N_B0 = N_B;

        /* Reorganize the samples in time order instead of frequency order */
        if (B0 > 1) {
            if (lowband != null) {
                deinterleave_hadamard(lowband, lowband_ptr, N_B >> recombine, B0 << recombine, longBlocks);
            }
        }

        int cm = quant_partition(ctx, X, X_ptr, N, b, B, lowband, lowband_ptr, LM, gain, fill);

        /* This code is used by the decoder and by the resynthesis-enabled encoder */
        /* Undo the sample reorganization going from time order to frequency order */
        if (B0 > 1) {
            interleave_hadamard(X, X_ptr, N_B >> recombine, B0 << recombine, longBlocks);
        }

        /* Undo time-freq changes that we did earlier */
        N_B = N_B0;
        B = B0;
        for (k = 0; k < time_divide; k++) {
            B >>= 1;
            N_B <<= 1;
            cm |= cm >> B;
            haar1(X, X_ptr, N_B, B);
        }

        int N0 = N;
        for (k = 0; k < recombine; k++) {
            cm = bit_deinterleave_table[cm];
            haar1(X, X_ptr, N0 >> k, 1 << k);
        }
        B <<= recombine;

        /* Scale output for later folding */
        if (lowband_out != null) {
            float n = InlinesF.celt_sqrt(N0);
            for (int j = 0; j < N0; j++) {
                lowband_out[lowband_out_ptr + j] = n * X[X_ptr + j];
            }
        }

        cm = cm & ((1 << B) - 1);
        return cm;
    }

    /* This function is responsible for encoding and decoding a band for the stereo case. */
    static int quant_band_stereo(band_ctx ctx, float[] X, int X_ptr, float[] Y, int Y_ptr,
                                 int N, int b, int B, float[] lowband, int lowband_ptr,
                                 int LM, float[] lowband_out, int lowband_out_ptr,
                                 float[] lowband_scratch, int lowband_scratch_ptr, int fill) {
        split_ctx sctx = new split_ctx(); // porting note: stack var

        //porting note: pointer
        EntropyCoder ec = ctx.ec;

        /* Special case for one sample */
        if (N == 1) {
            return quant_band_n1(ctx, X, X_ptr, Y, Y_ptr, lowband_out, lowband_out_ptr);
        }

        int orig_fill = fill;

        BoxedValueInt boxed_b = new BoxedValueInt(b);
        BoxedValueInt boxed_fill = new BoxedValueInt(fill);
        compute_theta(ctx, sctx, N, boxed_b, B, B, LM, 1, boxed_fill);
        b = boxed_b.Val;
        fill = boxed_fill.Val;

        int inv = sctx.inv;
        int delta = sctx.delta;
        int itheta = sctx.itheta;
        int qalloc = sctx.qalloc;
        float mid = sctx.imid * (1.0f / 32768.0f);
        float side = sctx.iside * (1.0f / 32768.0f);

        /* This is a special case for N=2 that only works for stereo and takes
           advantage of the fact that mid and side are orthogonal to encode
           the side with just one bit. */
        int sbits;
        int mbits;
        int cm;
        if (N == 2) {
            mbits = b;
            sbits = 0;
            /* Only need one bit for the side. */
            if (itheta != 0 && itheta != 16384) {
                sbits = 1 << EntropyCoder.BITRES;
            }
            mbits -= sbits;
            int c = itheta > 8192 ? 1 : 0;
            ctx.remaining_bits -= qalloc + sbits;
            int x2_ptr;
            float[] y2;
            float[] x2;
            if (c != 0) {
                x2 = Y;
                x2_ptr = Y_ptr;
                y2 = X;
            } else {
                x2 = X;
                x2_ptr = X_ptr;
                y2 = Y;
            }

            int sign = 0;
            if (sbits != 0) {
                sign = ec.dec_bits(1);
            }
            sign = sign * 2 - 1;
            /* We use orig_fill here because we want to fold the side, but if
               itheta==16384, we'll have cleared the low bits of fill. */
            cm = quant_band(ctx, x2, x2_ptr, N, mbits, B, lowband, lowband_ptr,
                    LM, lowband_out, lowband_out_ptr, 1.0f, lowband_scratch, lowband_scratch_ptr, orig_fill);

            /* We don't split N=2 bands, so cm is either 1 or 0 (for a fold-collapse),
               and there's no need to worry about mixing with the other channel. */
            y2[Y_ptr] = ((-sign) * x2[x2_ptr + 1]);
            y2[Y_ptr + 1] = (sign * x2[x2_ptr]);
            X[X_ptr] = mid * X[X_ptr];
            X[X_ptr + 1] = mid * X[X_ptr + 1];
            Y[Y_ptr] = side * Y[Y_ptr];
            Y[Y_ptr + 1] = side * Y[Y_ptr + 1];
            float tmp = X[X_ptr];
            X[X_ptr] = tmp - Y[Y_ptr];
            Y[Y_ptr] = tmp + Y[Y_ptr];
            tmp = X[X_ptr + 1];
            X[X_ptr + 1] = tmp - Y[Y_ptr + 1];
            Y[Y_ptr + 1] = tmp + Y[Y_ptr + 1];
        } else {
            /* "Normal" split code */

            mbits = Math.max(0, Math.min(b, (b - delta) / 2));
            sbits = b - mbits;
            ctx.remaining_bits -= qalloc;

            int rebalance = ctx.remaining_bits;
            if (mbits >= sbits) {
                /* In stereo mode, we do not apply a scaling to the mid because we need the normalized
                   mid for folding later. */
                cm = quant_band(ctx, X, X_ptr, N, mbits, B,
                        lowband, lowband_ptr, LM, lowband_out, lowband_out_ptr,
                        1.0f, lowband_scratch, lowband_scratch_ptr, fill);
                rebalance = mbits - (rebalance - ctx.remaining_bits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 0) {
                    sbits += rebalance - (3 << EntropyCoder.BITRES);
                }

                /* For a stereo split, the high bits of fill are always zero, so no
                   folding will be done to the side. */
                cm |= quant_band(ctx, Y, Y_ptr, N, sbits, B,
                        null, 0, LM, null, 0,
                        side, null, 0, fill >> B);
            } else {
                /* For a stereo split, the high bits of fill are always zero, so no
                   folding will be done to the side. */
                cm = quant_band(ctx, Y, Y_ptr, N, sbits, B,
                        null, 0, LM, null, 0,
                        side, null, 0, fill >> B);
                rebalance = sbits - (rebalance - ctx.remaining_bits);
                if (rebalance > 3 << EntropyCoder.BITRES && itheta != 16384) {
                    mbits += rebalance - (3 << EntropyCoder.BITRES);
                }
                /* In stereo mode, we do not apply a scaling to the mid because we need the normalized
                   mid for folding later. */
                cm |= quant_band(ctx, X, X_ptr, N, mbits, B,
                        lowband, lowband_ptr, LM, lowband_out, lowband_out_ptr,
                        1.0f, lowband_scratch, lowband_scratch_ptr, fill);
            }
        }


        /* This code is used by the decoder and by the resynthesis-enabled encoder */
        if (N != 2) {
            stereo_merge(X, X_ptr, Y, Y_ptr, mid, N);
        }
        if (inv != 0) {
            for (int j = Y_ptr; j < N + Y_ptr; j++) {
                Y[j] = -Y[j];
            }
        }

        return cm;
    }

    static void quant_all_bands(CeltMode m, int start, int end,
                                float[] X_, float[] Y_, short[] collapse_masks,
                                float[][] bandE, int[] pulses, int shortBlocks, int spread,
                                int dual_stereo, int intensity, int[] tf_res, int total_bits,
                                int balance, EntropyCoder ec, int LM, int codedBands,
                                BoxedValueInt seed) {
        short[] eBands = m.eBands;
        int C = Y_ != null ? 2 : 1;
        band_ctx ctx = new band_ctx(); // porting note: stack var

        int M = 1 << LM;
        int B = (shortBlocks != 0) ? M : 1;
        int norm_offset = M * eBands[start];

        /* No need to allocate norm for the last band because we don't need an
           output in that band. */
        float[] norm = new float[(C * (M * eBands[m.nbEBands - 1] - norm_offset))];
        int norm2 = M * eBands[m.nbEBands - 1] - norm_offset;

        /* We can use the last band as scratch space because we don't need that
           scratch space for the last band. */
        float[] lowband_scratch = X_;
        int lowband_scratch_ptr = M * eBands[m.nbEBands - 1];

        ctx.ec = ec;
        ctx.intensity = intensity;
        ctx.m = m;
        ctx.seed = seed.Val;
        ctx.spread = spread;
        int lowband_offset = 0;
        int update_lowband = 1;
        for (int i = start; i < end; i++) {

            ctx.i = i;
            int last = (i == end - 1) ? 1 : 0;

            float[] X = X_;
            int X_ptr = (M * eBands[i]);
            int Y_ptr = 0;
            float[] Y;
            if (Y_ != null) {
                Y = Y_;
                Y_ptr = (M * eBands[i]);
            } else {
                Y = null;
            }
            int N = M * eBands[i + 1] - M * eBands[i];
            int tell = ec.tell_frac();

            /* Compute how many bits we want to allocate to this band */
            if (i != start) {
                balance -= tell;
            }
            int remaining_bits = total_bits - tell - 1;
            ctx.remaining_bits = remaining_bits;
            int b;
            if (i <= codedBands - 1) {
                int curr_balance = Inlines.celt_sudiv(balance, Math.min(3, codedBands - i));
                b = Math.max(0, Math.min(16383, Math.min(remaining_bits + 1, pulses[i] + curr_balance)));
            } else {
                b = 0;
            }

            if (M * eBands[i] - N >= M * eBands[start] && (update_lowband != 0 || lowband_offset == 0)) {
                lowband_offset = i;
            }

            int tf_change = tf_res[i];
            ctx.tf_change = tf_change;
            if (i >= m.effEBands) {
                X = norm;
                X_ptr = 0;
                if (Y_ != null) {
                    Y = norm;
                    Y_ptr = 0;
                }
                lowband_scratch = null;
            }
            if (i == end - 1) {
                lowband_scratch = null;
            }

            /* Get a conservative estimate of the collapse_mask's for the bands we're
               going to be folding from. */
            int y_cm;
            int x_cm;
            int effective_lowband = -1;
            if (lowband_offset != 0 && (spread != Spread.SPREAD_AGGRESSIVE || B > 1 || tf_change < 0)) {
                /* This ensures we never repeat spectral content within one band */
                effective_lowband = Math.max(0, M * eBands[lowband_offset] - norm_offset - N);
                int fold_start = lowband_offset;
                while (M * eBands[--fold_start] > effective_lowband + norm_offset) {
                }
                int fold_end = lowband_offset - 1;
                while (M * eBands[++fold_end] < effective_lowband + norm_offset + N) {
                }
                x_cm = y_cm = 0;
                int fold_i = fold_start;
                do {
                    x_cm |= collapse_masks[fold_i * C];
                    y_cm |= collapse_masks[fold_i * C + C - 1];
                } while (++fold_i < fold_end);
            } /* Otherwise, we'll be using the LCG to fold, so all blocks will (almost
               always) be non-zero. */ else {
                x_cm = y_cm = ((1 << B) - 1);
            }

            if (dual_stereo != 0 && i == intensity) {

                /* Switch off dual stereo to do intensity. */
                dual_stereo = 0;
                for (int j = 0; j < M * eBands[i] - norm_offset; j++) {
                    norm[j] = .5f * (norm[j] + norm[norm2 + j]);
                }
            }
            if (dual_stereo != 0) {
                x_cm = quant_band(ctx,
                        X,
                        X_ptr,
                        N,
                        b / 2,
                        B,
                        effective_lowband != -1 ? norm : null,
                        effective_lowband,
                        LM,
                        last != 0 ? null : norm,
                        M * eBands[i] - norm_offset,
                        1.0f,
                        lowband_scratch,
                        lowband_scratch_ptr,
                        x_cm);
                y_cm = quant_band(
                        ctx,
                        Y,
                        Y_ptr,
                        N,
                        b / 2,
                        B,
                        effective_lowband != -1 ? norm : null,
                        norm2 + effective_lowband,
                        LM,
                        last != 0 ? null : norm,
                        norm2 + (M * eBands[i] - norm_offset),
                        1.0f,
                        lowband_scratch,
                        lowband_scratch_ptr,
                        y_cm);
            } else {
                if (Y != null) {
                    x_cm = quant_band_stereo(
                            ctx,
                            X,
                            X_ptr,
                            Y,
                            Y_ptr,
                            N,
                            b,
                            B,
                            effective_lowband != -1 ? norm : null,
                            effective_lowband,
                            LM,
                            last != 0 ? null : norm,
                            M * eBands[i] - norm_offset,
                            lowband_scratch,
                            lowband_scratch_ptr,
                            x_cm | y_cm);
                } else {
                    x_cm = quant_band(
                            ctx,
                            X,
                            X_ptr,
                            N,
                            b,
                            B,
                            effective_lowband != -1 ? norm : null,
                            effective_lowband,
                            LM,
                            last != 0 ? null : norm,
                            M * eBands[i] - norm_offset,
                            1.0f,
                            lowband_scratch,
                            lowband_scratch_ptr,
                            x_cm | y_cm); // opt: lots of pointers are created here too
                }
                y_cm = x_cm;
            }
            collapse_masks[i * C] = (short) (x_cm & 0xFF);
            collapse_masks[i * C + C - 1] = (short) (y_cm & 0xFF);
            balance += pulses[i] + tell;

            /* Update the folding position only as long as we have 1 bit/sample depth. */
            update_lowband = (b > (N << EntropyCoder.BITRES)) ? 1 : 0;
        }

        seed.Val = ctx.seed;
    }
}
