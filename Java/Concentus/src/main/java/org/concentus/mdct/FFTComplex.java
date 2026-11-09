package org.concentus.mdct;

import java.util.Arrays;
import java.util.Objects;

public final class FFTComplex {
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

    public FFTComplex(int n) {
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
            Complex.expI(-2 * Math.PI * i / n).store(twiddle, i * 2);
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

            Complex x0 = Complex.load(src, i0);
            Complex x1 = Complex.load(src, i1);

            x0.add(x1).store(dst, o/**/);
            x0.sub(x1).store(dst, o + 2);
        }
    }

    private void radix4First(float[] src, int srcOff, float[] dst, int dstOff) {
        for (int i = 0, o = dstOff; i < reorder.length; i += 4, o += 8) {
            int i0 = srcOff + 2 * reorder[i/**/];
            int i1 = srcOff + 2 * reorder[i + 1];
            int i2 = srcOff + 2 * reorder[i + 2];
            int i3 = srcOff + 2 * reorder[i + 3];

            Complex x0 = Complex.load(src, i0);
            Complex x1 = Complex.load(src, i1);
            Complex x2 = Complex.load(src, i2);
            Complex x3 = Complex.load(src, i3);

            Complex a0 = x0.add(x2);
            Complex a1 = x1.add(x3);
            Complex b0 = x0.sub(x2);
            Complex b1 = x1.sub(x3).mulNegI();

            a0.add(a1).store(dst, o/**/);
            b0.add(b1).store(dst, o + 2);
            a0.sub(a1).store(dst, o + 4);
            b0.sub(b1).store(dst, o + 6);
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

                Complex x0 = Complex.load(x, i0);
                Complex x1 = Complex.load(x, i1).mul(Complex.load(w, t1/**/));
                Complex x2 = Complex.load(x, i2).mul(Complex.load(w, t1 * 2));

                Complex a0 = x1.add(x2);
                Complex b0 = x1.sub(x2).mulNegI();

                Complex c0 = x0.addMul(a0, -COS_1_3); // x0 + a0 cos(2pi/3)

                x0.add(a0).store(x, i0);
                c0.addMul(b0, +SIN_1_3).store(x, i1); // c0 + b0 sin(2pi/3)
                c0.addMul(b0, -SIN_1_3).store(x, i2); // c0 - b0 sin(2pi/3)
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

                Complex x0 = Complex.load(x, i0);
                Complex x1 = Complex.load(x, i1).mul(Complex.load(w, t1/**/));
                Complex x2 = Complex.load(x, i2).mul(Complex.load(w, t1 * 2));
                Complex x3 = Complex.load(x, i3).mul(Complex.load(w, t1 * 3));

                Complex a0 = x0.add(x2);
                Complex a1 = x1.add(x3);
                Complex b0 = x0.sub(x2);
                Complex b1 = x1.sub(x3).mulNegI();

                a0.add(a1).store(x, i0);
                b0.add(b1).store(x, i1);
                a0.sub(a1).store(x, i2);
                b0.sub(b1).store(x, i3);
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

                Complex x0 = Complex.load(x, i0);
                Complex x1 = Complex.load(x, i1).mul(Complex.load(w, t1/**/));
                Complex x2 = Complex.load(x, i2).mul(Complex.load(w, t1 * 2));
                Complex x3 = Complex.load(x, i3).mul(Complex.load(w, t1 * 3));
                Complex x4 = Complex.load(x, i4).mul(Complex.load(w, t1 * 4));

                Complex a0 = x1.add(x4);
                Complex a1 = x2.add(x3);
                Complex b0 = x1.sub(x4).mulNegI();
                Complex b1 = x2.sub(x3).mulNegI();

                Complex c0 = x0.addMul(a0, +COS_2_5).addMul(a1, -COS_1_5); // x0 + a0 cos(2pi/5) + a1 cos(4pi/5)
                Complex c1 = x0.addMul(a0, -COS_1_5).addMul(a1, +COS_2_5); // x0 + a0 cos(4pi/5) + a1 cos(8pi/5)
                Complex d0 = b0.mul(SIN_2_5).addMul(b1, +SIN_1_5);         //      b0 sin(2pi/5) + b1 sin(4pi/5)
                Complex d1 = b0.mul(SIN_1_5).addMul(b1, -SIN_2_5);         //      b0 sin(4pi/5) + b1 sin(8pi/5)

                x0.add(a0).add(a1).store(x, i0);
                c0.add(d0).store(x, i1);
                c1.add(d1).store(x, i2);
                c1.sub(d1).store(x, i3);
                c0.sub(d0).store(x, i4);
            }
        }
    }
}
