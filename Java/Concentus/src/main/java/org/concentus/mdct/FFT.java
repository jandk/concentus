package org.concentus.mdct;

import java.util.Arrays;
import java.util.Objects;

public final class FFT {
    private static final float COS_1_3 = 0.50000000000000000000000000000000f;
    private static final float SIN_1_3 = 0.86602540378443864676372317075294f;

    private static final float COS_1_5 = 0.80901699437494742410229341718282f;
    private static final float SIN_1_5 = 0.58778525229247312916870595463907f;
    private static final float COS_2_5 = 0.30901699437494742410229341718282f;
    private static final float SIN_2_5 = 0.95105651629515357211643933337938f;

    private static final int[] RADICES = {4, 3, 5};

    private final int n;
    private final int[] factors;
    private final short[] reorder;
    private final float[] twiddle;
    private final float[] scratch;

    public FFT(int n) {
        if (n < 2 || n > 32768) {
            throw new IllegalArgumentException("n must be between 2 and 32768");
        }
        this.n = n;
        this.factors = factorize(n);
        this.reorder = buildReorder(n, this.factors);
        this.twiddle = buildTwiddle(n);
        this.scratch = new float[n * 2];
    }

    private static int[] factorize(int n) {
        int[] factors = new int[16];
        int count = 0;

        int twos = Integer.numberOfTrailingZeros(n);
        if ((twos & 1) == 1) {
            factors[count++] = 2;
            n /= 2;
        }
        for (int i : RADICES) {
            while (n % i == 0) {
                factors[count++] = i;
                n /= i;
            }
        }
        if (n != 1) {
            throw new IllegalArgumentException("n must factor into 2, 3 and 5. Remaining: " + n);
        }
        return Arrays.copyOf(factors, count);
    }

    private static short[] buildReorder(int n, int[] factors) {
        short[] map = new short[n];
        expand(map, factors, 0, 1);
        return map;
    }

    private static void expand(short[] map, int[] factors, int level, int count) {
        if (level == factors.length) {
            return;
        }
        int p = factors[level];
        for (int i = 0; i < count; i++) {
            int m = map[i];
            for (int q = 0; q < p; q++) {
                map[q * count + i] = (short) (q + p * m);
            }
        }
        expand(map, factors, level + 1, count * p);
    }

    private static float[] buildTwiddle(int n) {
        float[] twiddle = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double jth = 2 * Math.PI * i / n;
            twiddle[i * 2/**/] = (float) Math.cos(jth);
            twiddle[i * 2 + 1] = (float) -Math.sin(jth);
        }
        return twiddle;
    }

    public void forward(float[] x) {
        if (x.length != 2 * n) {
            throw new IllegalArgumentException("x must hold exactly n complex values");
        }
        forward(x, 0, scratch, 0);
        System.arraycopy(scratch, 0, x, 0, x.length);
    }

    public void forward(float[] src, int srcOff, float[] dst, int dstOff) {
        int len = 2 * n;
        Objects.checkFromIndexSize(srcOff, len, src.length);
        Objects.checkFromIndexSize(dstOff, len, dst.length);
        if (src == dst && srcOff < dstOff + len && dstOff < srcOff + len) {
            throw new IllegalArgumentException("src and dst ranges must not overlap");
        }
        passes(dst, dstOff, firstStage(src, srcOff, dst, dstOff));
    }

    int firstStage(float[] src, int srcOff, float[] dst, int dstOff) {
        switch (factors[0]) {
            case 2:
                radix2First(src, srcOff, dst, dstOff);
                return 1;
            case 4:
                radix4First(src, srcOff, dst, dstOff);
                return 1;
            default:
                gather(src, srcOff, dst, dstOff);
                return 0;
        }
    }

    private void radix2First(float[] src, int srcOff, float[] dst, int dstOff) {
        for (int i = 0, o = dstOff; i < reorder.length; i += 2, o += 4) {
            int i0 = srcOff + 2 * reorder[i/**/];
            int i1 = srcOff + 2 * reorder[i + 1];

            float x0r = src[i0], x0i = src[i0 + 1];
            float x1r = src[i1], x1i = src[i1 + 1];

            // @formatter:off
            dst[o/**/] = x0r + x1r; dst[o + 1] = x0i + x1i; // x0 + x1
            dst[o + 2] = x0r - x1r; dst[o + 3] = x0i - x1i; // x0 - x1
            // @formatter:on
        }
    }

    private void radix4First(float[] src, int srcOff, float[] dst, int dstOff) {
        for (int i = 0, o = dstOff; i < reorder.length; i += 4, o += 8) {
            int i0 = srcOff + 2 * reorder[i/**/];
            int i1 = srcOff + 2 * reorder[i + 1];
            int i2 = srcOff + 2 * reorder[i + 2];
            int i3 = srcOff + 2 * reorder[i + 3];

            float x0r = src[i0], x0i = src[i0 + 1];
            float x1r = src[i1], x1i = src[i1 + 1];
            float x2r = src[i2], x2i = src[i2 + 1];
            float x3r = src[i3], x3i = src[i3 + 1];

            float a0r = x0r + x2r, a0i = x0i + x2i; // (x0 + x2)
            float a1r = x1r + x3r, a1i = x1i + x3i; // (x1 + x3)
            float b0r = x0r - x2r, b0i = x0i - x2i; // (x0 - x2)
            float b1r = x1i - x3i, b1i = x3r - x1r; // (x1 - x3) * -i

            // @formatter:off
            dst[o/**/] = a0r + a1r; dst[o + 1] = a0i + a1i; // a0 + a1
            dst[o + 2] = b0r + b1r; dst[o + 3] = b0i + b1i; // b0 + b1
            dst[o + 4] = a0r - a1r; dst[o + 5] = a0i - a1i; // a0 - a1
            dst[o + 6] = b0r - b1r; dst[o + 7] = b0i - b1i; // b0 - b1
            // @formatter:on
        }
    }

    void gather(float[] src, int srcOff, float[] dst, int dstOff) {
        for (int i = 0, o = dstOff; i < reorder.length; i++, o += 2) {
            int i0 = srcOff + 2 * reorder[i];
            dst[o/**/] = src[i0/**/];
            dst[o + 1] = src[i0 + 1];
        }
    }

    void passes(float[] x, int off, int start) {
        int span = 2;
        for (int i = 0; i < start; i++) {
            span *= factors[i];
        }
        for (int i = start; i < factors.length; i++) {
            int factor = factors[i];
            int step = 4 * n / (factor * span);
            switch (factor) {
                case 3:
                    radix3(x, off, span, step);
                    break;
                case 4:
                    radix4(x, off, span, step);
                    break;
                case 5:
                    radix5(x, off, span, step);
                    break;
                default:
                    throw new UnsupportedOperationException();
            }
            span *= factor;
        }
    }

    private void radix3(float[] x, int off, int span, int step) {
        float[] w = twiddle;
        for (int base = off, end = off + 2 * n; base < end; base += 3 * span) {
            for (int i0 = base, t1 = 0; i0 < base + span; i0 += 2, t1 += step) {
                int i1 = i0 + span;
                int i2 = i1 + span;

                float xr, xi, wr, wi;

                // @formatter:off
                float x0r = x[i0], x0i = x[i0 + 1];

                xr = x[i1]; xi = x[i1 + 1]; wr = w[t1    ]; wi = w[t1     + 1];
                float x1r = xr * wr - xi * wi, x1i = xr * wi + xi * wr; // x1 * w^1

                xr = x[i2]; xi = x[i2 + 1]; wr = w[t1 * 2]; wi = w[t1 * 2 + 1];
                float x2r = xr * wr - xi * wi, x2i = xr * wi + xi * wr; // x2 * w^2

                float a0r = x1r + x2r, a0i = x1i + x2i; // (x1 + x2)
                float b0r = x1i - x2i, b0i = x2r - x1r; // (x1 - x2) * -i

                float c0r = x0r - a0r * COS_1_3, c0i = x0i - a0i * COS_1_3; // x0 + a0 cos(2pi/3)
                float d0r =       b0r * SIN_1_3, d0i =       b0i * SIN_1_3; //      b0 sin(2pi/3)

                x[i0] = x0r + a0r; x[i0 + 1] = x0i + a0i; // x0 + a0
                x[i1] = c0r + d0r; x[i1 + 1] = c0i + d0i; // c0 + d0
                x[i2] = c0r - d0r; x[i2 + 1] = c0i - d0i; // c0 - d0
                // @formatter:on
            }
        }
    }

    private void radix4(float[] x, int off, int span, int step) {
        float[] w = twiddle;
        for (int base = off, end = off + 2 * n; base < end; base += 4 * span) {
            for (int i0 = base, t1 = 0; i0 < base + span; i0 += 2, t1 += step) {
                int i1 = i0 + span;
                int i2 = i1 + span;
                int i3 = i2 + span;

                float xr, xi, wr, wi;

                // @formatter:off
                float x0r = x[i0], x0i = x[i0 + 1];

                xr = x[i1]; xi = x[i1 + 1]; wr = w[t1    ]; wi = w[t1     + 1];
                float x1r = xr * wr - xi * wi, x1i = xr * wi + xi * wr; // x1 * w^1

                xr = x[i2]; xi = x[i2 + 1]; wr = w[t1 * 2]; wi = w[t1 * 2 + 1];
                float x2r = xr * wr - xi * wi, x2i = xr * wi + xi * wr; // x2 * w^2

                xr = x[i3]; xi = x[i3 + 1]; wr = w[t1 * 3]; wi = w[t1 * 3 + 1];
                float x3r = xr * wr - xi * wi, x3i = xr * wi + xi * wr; // x3 * w^3

                float a0r = x0r + x2r, a0i = x0i + x2i; // (x0 + x2)
                float a1r = x1r + x3r, a1i = x1i + x3i; // (x1 + x3)
                float b0r = x0r - x2r, b0i = x0i - x2i; // (x0 - x2)
                float b1r = x1i - x3i, b1i = x3r - x1r; // (x1 - x3) * -i

                x[i0] = a0r + a1r; x[i0 + 1] = a0i + a1i; // a0 + a1
                x[i1] = b0r + b1r; x[i1 + 1] = b0i + b1i; // b0 + b1
                x[i2] = a0r - a1r; x[i2 + 1] = a0i - a1i; // a0 - a1
                x[i3] = b0r - b1r; x[i3 + 1] = b0i - b1i; // b0 - b1
                // @formatter:on
            }
        }
    }

    private void radix5(float[] x, int off, int span, int step) {
        float[] w = twiddle;
        for (int base = off, end = off + 2 * n; base < end; base += 5 * span) {
            for (int i0 = base, t1 = 0; i0 < base + span; i0 += 2, t1 += step) {
                int i1 = i0 + span;
                int i2 = i1 + span;
                int i3 = i2 + span;
                int i4 = i3 + span;

                float xr, xi, wr, wi;

                // @formatter:off
                float x0r = x[i0], x0i = x[i0 + 1];

                xr = x[i1]; xi = x[i1 + 1]; wr = w[t1    ]; wi = w[t1     + 1];
                float x1r = xr * wr - xi * wi, x1i = xr * wi + xi * wr; // x1 * w^1

                xr = x[i2]; xi = x[i2 + 1]; wr = w[t1 * 2]; wi = w[t1 * 2 + 1];
                float x2r = xr * wr - xi * wi, x2i = xr * wi + xi * wr; // x2 * w^2

                xr = x[i3]; xi = x[i3 + 1]; wr = w[t1 * 3]; wi = w[t1 * 3 + 1];
                float x3r = xr * wr - xi * wi, x3i = xr * wi + xi * wr; // x3 * w^3

                xr = x[i4]; xi = x[i4 + 1]; wr = w[t1 * 4]; wi = w[t1 * 4 + 1];
                float x4r = xr * wr - xi * wi, x4i = xr * wi + xi * wr; // x4 * w^4

                float a0r = x1r + x4r, a0i = x1i + x4i; // (x1 + x4)
                float a1r = x2r + x3r, a1i = x2i + x3i; // (x2 + x3)
                float b0r = x1i - x4i, b0i = x4r - x1r; // (x1 - x4) * -i
                float b1r = x2i - x3i, b1i = x3r - x2r; // (x2 - x3) * -i

                float c0r = x0r + a0r * COS_2_5 - a1r * COS_1_5, c0i = x0i + a0i * COS_2_5 - a1i * COS_1_5; // x0 + a0 cos(2pi/5) + a1 cos(4pi/5)
                float c1r = x0r - a0r * COS_1_5 + a1r * COS_2_5, c1i = x0i - a0i * COS_1_5 + a1i * COS_2_5; // x0 + a0 cos(4pi/5) + a1 cos(8pi/5)
                float d0r =       b0r * SIN_2_5 + b1r * SIN_1_5, d0i =       b0i * SIN_2_5 + b1i * SIN_1_5; //      b0 sin(2pi/5) + b1 sin(4pi/5)
                float d1r =       b0r * SIN_1_5 - b1r * SIN_2_5, d1i =       b0i * SIN_1_5 - b1i * SIN_2_5; //      b0 sin(4pi/5) + b1 sin(8pi/5)

                x[i0] = x0r + a0r + a1r; x[i0 + 1] = x0i + a0i + a1i; // x0 + a0 + a1
                x[i1] =       c0r + d0r; x[i1 + 1] =       c0i + d0i; // c0 + d0
                x[i2] =       c1r + d1r; x[i2 + 1] =       c1i + d1i; // c1 + d1
                x[i3] =       c1r - d1r; x[i3 + 1] =       c1i - d1i; // c1 - d1
                x[i4] =       c0r - d0r; x[i4 + 1] =       c0i - d0i; // c0 - d0
                // @formatter:on
            }
        }
    }
}
