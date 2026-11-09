package org.concentus.mdct;

import java.util.Objects;

public final class MDCT {
    private final int n;
    private final FFT fft;
    private final float[] twiddle;
    private final float[] scratch;

    public MDCT(int n) {
        if (n < 8 || n > 131072 || n % 8 != 0) {
            throw new IllegalArgumentException("n must be a multiple of 8 between 8 and 131072");
        }
        this.n = n;
        int n2 = n >>> 1;
        int n4 = n >>> 2;
        this.fft = new FFT(n4);
        this.twiddle = buildTwiddle();
        this.scratch = new float[n2];
    }

    private float[] buildTwiddle() {
        int n2 = n >>> 1;
        int n4 = n >>> 2;
        float[] twiddle = new float[n2];
        for (int i = 0; i < n4; i++) {
            Complex.expI(-2 * Math.PI * (i + 0.125) / n).store(twiddle, i * 2);
        }
        return twiddle;
    }

    public void backward(float[] x, int off) {
        backward(x, off, x, off);
    }

    public void backward(float[] src, int srcOff, float[] dst, int dstOff) {
        int n2 = n >>> 1;
        Objects.checkFromIndexSize(srcOff, n2, src.length);
        Objects.checkFromIndexSize(dstOff, n, dst.length);

        int dstMid = dstOff + n2;
        if (src == dst && srcOff < dstMid + n2 && dstMid < srcOff + n2) {
            throw new IllegalArgumentException("src must not overlap the dstMid half of dst");
        }

        preRotate(src, srcOff, dst, dstMid);
        fft.forward(dst, dstMid, scratch, 0);
        postRotateAndUnfold(scratch, dst, dstOff);
    }

    private void preRotate(float[] src, int srcOff, float[] dst, int dstOff) {
        float[] w = twiddle;
        int n2 = n >>> 1;

        for (int p0 = 0, p1 = n2 - 1; p0 < n2; p0 += 2, p1 -= 2) {
            Complex x0 = Complex.load(src, srcOff + p0, srcOff + p1);
            Complex w0 = Complex.load(w, p0);
            x0.mul(w0).store(dst, dstOff + p0);
        }
    }

    private void postRotateAndUnfold(float[] src, float[] dst, int dstOff) {
        float[] w = twiddle;
        int q1 = n >>> 2;

        int y1 = dstOff - q1;
        int y2 = dstOff + q1;
        int y4 = dstOff + q1 * 3;

        for (int p0 = 0, p1 = 2 * q1 - 2; p0 < q1; p0 += 2, p1 -= 2) {
            Complex x0 = Complex.load(src, p0).mul(Complex.load(w, p0));
            Complex x1 = Complex.load(src, p1).mul(Complex.load(w, p1));

            // @formatter:off
            dst[y1 + p1] = +x1.re(); dst[y1 + p1 + 1] = -x0.im(); // 1st quarter
            dst[y2 + p0] = +x0.im(); dst[y2 + p0 + 1] = -x1.re(); // 2nd quarter
            dst[y2 + p1] = +x1.im(); dst[y2 + p1 + 1] = -x0.re(); // 3rd quarter
            dst[y4 + p0] = -x0.re(); dst[y4 + p0 + 1] = +x1.im(); // 4th quarter
            // @formatter:on
        }
    }
}
