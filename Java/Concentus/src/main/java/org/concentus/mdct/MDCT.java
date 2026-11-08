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
            double angle = 2 * Math.PI * (i + 0.125) / n;
            twiddle[i * 2/**/] = (float) +Math.cos(angle);
            twiddle[i * 2 + 1] = (float) -Math.sin(angle);
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
            float xr = src[srcOff + p0];
            float xi = src[srcOff + p1];
            float wr = w[p0];
            float wi = w[p0 + 1];
            dst[dstOff + p0/**/] = xr * wr - xi * wi;
            dst[dstOff + p0 + 1] = xr * wi + xi * wr;
        }
    }

    private void postRotateAndUnfold(float[] src, float[] dst, int dstOff) {
        float[] w = twiddle;
        int q1 = n >>> 2;

        int y1 = dstOff - q1;
        int y2 = dstOff + q1;
        int y4 = dstOff + q1 * 3;

        for (int p0 = 0, p1 = 2 * q1 - 2; p0 < q1; p0 += 2, p1 -= 2) {
            float xr, xi, wr, wi;

            // @formatter:off
            xr = src[p0]; xi = src[p0 + 1];
            wr =   w[p0]; wi =   w[p0 + 1];
            float x0r = xr * wr - xi * wi;
            float x0i = xr * wi + xi * wr;

            xr = src[p1]; xi = src[p1 + 1];
            wr =   w[p1]; wi =   w[p1 + 1];
            float x1r = xr * wr - xi * wi;
            float x1i = xr * wi + xi * wr;

            dst[y1 + p1] = +x1r; dst[y1 + p1 + 1] = -x0i; // 1st quarter
            dst[y2 + p0] = +x0i; dst[y2 + p0 + 1] = -x1r; // 2nd quarter
            dst[y2 + p1] = +x1i; dst[y2 + p1 + 1] = -x0r; // 3rd quarter
            dst[y4 + p0] = -x0r; dst[y4 + p0 + 1] = +x1i; // 4th quarter
            // @formatter:on
        }
    }
}
