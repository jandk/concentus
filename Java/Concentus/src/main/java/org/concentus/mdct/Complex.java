package org.concentus.mdct;

public final class Complex {
    private final float re;
    private final float im;

    public Complex(float re, float im) {
        this.re = re;
        this.im = im;
    }

    public static Complex load(float[] array, int offset) {
        return load(array, offset, offset + 1);
    }

    public static Complex load(float[] array, int i0, int i1) {
        return new Complex(array[i0], array[i1]);
    }

    public static Complex expI(double theta) {
        return new Complex((float) Math.cos(theta), (float) Math.sin(theta));
    }

    public float re() {
        return re;
    }

    public float im() {
        return im;
    }

    public Complex add(Complex other) {
        return new Complex(re + other.re, im + other.im);
    }

    public Complex sub(Complex other) {
        return new Complex(re - other.re, im - other.im);
    }

    public Complex mul(Complex other) {
        return new Complex(
                Math.fma(re, other.re, -(im * other.im)),
                Math.fma(re, other.im, +(im * other.re))
        );
    }

    public Complex addMul(Complex c, float s) {
        return new Complex(
                Math.fma(c.re, s, re),
                Math.fma(c.im, s, im)
        );
    }

    public Complex mul(float scalar) {
        return new Complex(re * scalar, im * scalar);
    }

    public Complex mulNegI() {
        return new Complex(im, -re);
    }

    public void store(float[] array, int offset) {
        array[offset/**/] = re;
        array[offset + 1] = im;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Complex)) {
            return false;
        }

        Complex other = (Complex) obj;
        return Float.compare(re, other.re) == 0
                && Float.compare(im, other.im) == 0;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + Float.hashCode(re);
        result = 31 * result + Float.hashCode(im);
        return result;
    }

    @Override
    public String toString() {
        return "(" + re + ", " + im + ")";
    }
}
