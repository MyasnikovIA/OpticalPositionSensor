package ru.miacomsoft.optical_position_sensor;

import java.util.Arrays;

public class OpticalFlowTracker {

    public static final class Result {
        public boolean valid;
        public double dxPx, dyPx, distancePx, directionDeg, rotationDeg;
        public int blocksUsed, blocksTotal, blocksPassedQuality;
        public double confidence;
        public long processNs;
        public int[] featureXs = new int[0];
        public int[] featureYs = new int[0];
        public int featureCount;
    }

    private int block    = 8;
    private int search   = 4;
    private int maxSadPx = 18;
    private int targetWidth = 112;
    private int outlierPx   = 2;

    // --- Шумоподавление ---
    private int     minVariance        = 400;    // дисперсия блока (stddev ≈ 20)
    private int     minGradient        = 120;    // min(Σ|gx|, Σ|gy|) на блок — «уголковость»
    private boolean subpixel           = true;
    private boolean temporalSmoothing  = true;
    private double  temporalAlpha      = 0.6;    // 1.0 = без сглаживания, 0.05 = макс.
    private double  maxMotionPx        = 40.0;   // motion gate (в пикселях превью)

    private int downscale = 2;
    private int gw, gh, nx, ny, totalBlocks;
    private int lastW = -1, lastH = -1;

    private byte[] prev = new byte[0];
    private byte[] cur  = new byte[0];
    private boolean hasPrev;

    private int[]    idx   = new int[0];
    private int[]    dxs   = new int[0];
    private int[]    dys   = new int[0];
    private double[] subDx = new double[0];
    private double[] subDy = new double[0];
    private int[]    tmp   = new int[0];

    private double  smoothDx = 0, smoothDy = 0;
    private boolean hasSmooth = false;

    public int getDownscale()   { return downscale; }
    public int getGridWidth()   { return gw; }
    public int getGridHeight()  { return gh; }
    public int getBlockSize()   { return block; }
    public byte[] getPrevBuffer() { return prev; }

    public void configure(int targetWidth, int search, int block,
                          int minVariance, int minGradient, int maxSadPx,
                          boolean subpixel, boolean temporalSmoothing,
                          double temporalAlpha, double maxMotionPx) {
        this.targetWidth       = Math.max(32, targetWidth);
        this.search            = Math.max(1, search);
        this.block             = Math.max(4, block);
        this.minVariance       = Math.max(0, minVariance);
        this.minGradient       = Math.max(0, minGradient);
        this.maxSadPx          = Math.max(1, maxSadPx);
        this.subpixel          = subpixel;
        this.temporalSmoothing = temporalSmoothing;
        this.temporalAlpha     = Math.max(0.05, Math.min(1.0, temporalAlpha));
        this.maxMotionPx       = Math.max(1.0, maxMotionPx);
        this.lastW = -1;
    }

    public void reset(int width, int height) {
        downscale = 1;
        while (width / downscale > targetWidth) downscale++;

        int w = width / downscale;
        int h = height / downscale;
        gw = Math.max(block, (w / block) * block);
        gh = Math.max(block, (h / block) * block);
        nx = gw / block;
        ny = gh / block;
        totalBlocks = nx * ny;

        prev  = new byte[gw * gh];
        cur   = new byte[gw * gh];
        idx   = new int[totalBlocks];
        dxs   = new int[totalBlocks];
        dys   = new int[totalBlocks];
        subDx = new double[totalBlocks];
        subDy = new double[totalBlocks];
        tmp   = new int[totalBlocks];

        hasPrev = false;
        hasSmooth = false;
        lastW = width;
        lastH = height;
    }

    public Result update(byte[] yPlane, int width, int height) {
        long t0 = System.nanoTime();
        Result res = new Result();

        if (width != lastW || height != lastH) reset(width, height);

        // downscale
        for (int yy = 0; yy < gh; yy++) {
            int src = yy * downscale * width;
            int dst = yy * gw;
            for (int xx = 0; xx < gw; xx++) cur[dst + xx] = yPlane[src + xx * downscale];
        }

        if (!hasPrev) {
            swapBuffers();
            hasPrev = true;
            res.processNs = System.nanoTime() - t0;
            return res;
        }

        int good = 0, passedQuality = 0;
        final int maxSad = maxSadPx * block * block;
        final int N = block * block;

        for (int by = 0; by < ny; by++) {
            int oy = by * block;
            for (int bx = 0; bx < nx; bx++) {
                int ox = bx * block;

                // --- gate 1: variance ---
                int sum = 0, sumSq = 0;
                for (int ry = 0; ry < block; ry++) {
                    int row = (oy + ry) * gw + ox;
                    for (int rx = 0; rx < block; rx++) {
                        int v = prev[row + rx] & 0xFF;
                        sum += v; sumSq += v * v;
                    }
                }
                int mean = sum / N;
                int variance = (sumSq / N) - mean * mean;
                if (variance < minVariance) continue;

                // --- gate 2: corner-likeness (both directions) ---
                int gxSum = 0, gySum = 0;
                for (int ry = 0; ry < block - 1; ry++) {
                    int row = (oy + ry) * gw + ox;
                    int nextRow = row + gw;
                    for (int rx = 0; rx < block - 1; rx++) {
                        int c = prev[row + rx] & 0xFF;
                        gxSum += Math.abs((prev[row + rx + 1] & 0xFF) - c);
                        gySum += Math.abs((prev[nextRow + rx] & 0xFF) - c);
                    }
                }
                if (Math.min(gxSum, gySum) < minGradient) continue;
                passedQuality++;

                // --- block matching (early exit) ---
                int bestSad = Integer.MAX_VALUE;
                int bdx = 0, bdy = 0;
                for (int sy = -search; sy <= search; sy++) {
                    int cy = oy + sy;
                    if (cy < 0 || cy + block > gh) continue;
                    for (int sx = -search; sx <= search; sx++) {
                        int cx = ox + sx;
                        if (cx < 0 || cx + block > gw) continue;
                        int sad = 0;
                        for (int ry = 0; ry < block && sad < bestSad; ry++) {
                            int prow = (oy + ry) * gw + ox;
                            int crow = (cy + ry) * gw + cx;
                            for (int rx = 0; rx < block; rx++) {
                                sad += Math.abs((prev[prow + rx] & 0xFF)
                                        - (cur[crow + rx] & 0xFF));
                            }
                        }
                        if (sad < bestSad) { bestSad = sad; bdx = sx; bdy = sy; }
                    }
                }
                if (bestSad > maxSad) continue;

                // --- sub-pixel ---
                double fdx = bdx, fdy = bdy;
                if (subpixel) {
                    int c   = bestSad;
                    int lx  = sadAt(prev, cur, gw, gh, ox, oy, bdx - 1, bdy, block);
                    int rx  = sadAt(prev, cur, gw, gh, ox, oy, bdx + 1, bdy, block);
                    int ty  = sadAt(prev, cur, gw, gh, ox, oy, bdx, bdy - 1, block);
                    int by2 = sadAt(prev, cur, gw, gh, ox, oy, bdx, bdy + 1, block);
                    double dX = lx - 2.0 * c + rx;
                    double dY = ty - 2.0 * c + by2;
                    if (Math.abs(dX) > 1e-6) {
                        double d = 0.5 * (lx - rx) / dX;
                        if (d > -0.5 && d < 0.5) fdx += d;
                    }
                    if (Math.abs(dY) > 1e-6) {
                        double d = 0.5 * (ty - by2) / dY;
                        if (d > -0.5 && d < 0.5) fdy += d;
                    }
                }

                idx[good]   = by * nx + bx;
                dxs[good]   = bdx;
                dys[good]   = bdy;
                subDx[good] = fdx;
                subDy[good] = fdy;
                good++;
            }
        }

        swapBuffers();

        res.blocksTotal         = totalBlocks;
        res.blocksPassedQuality = passedQuality;
        res.blocksUsed          = good;

        int minGood = Math.max(6, totalBlocks / 12);
        if (good < minGood) {
            res.processNs = System.nanoTime() - t0;
            return res;
        }

        // медиана
        System.arraycopy(dxs, 0, tmp, 0, good);
        Arrays.sort(tmp, 0, good);
        int medX = tmp[good / 2];
        System.arraycopy(dys, 0, tmp, 0, good);
        Arrays.sort(tmp, 0, good);
        int medY = tmp[good / 2];

        // отсечка выбросов
        double sumU = 0, sumV = 0;
        int n = 0;
        for (int i = 0; i < good; i++) {
            if (Math.abs(dxs[i] - medX) > outlierPx) continue;
            if (Math.abs(dys[i] - medY) > outlierPx) continue;
            sumU += subDx[i]; sumV += subDy[i]; n++;
        }
        if (n < 4) { res.blocksUsed = n; res.processNs = System.nanoTime() - t0; return res; }
        double meanU = sumU / n;
        double meanV = sumV / n;

        // поворот
        double cx = (gw - 1) * 0.5, cy = (gh - 1) * 0.5;
        double num = 0, den = 0;
        for (int i = 0; i < good; i++) {
            if (Math.abs(dxs[i] - medX) > outlierPx) continue;
            if (Math.abs(dys[i] - medY) > outlierPx) continue;
            int b = idx[i];
            double px = (b % nx) * block + block * 0.5 - cx;
            double py = (b / nx) * block + block * 0.5 - cy;
            num += (subDx[i] - meanU) * (-py) + (subDy[i] - meanV) * px;
            den += py * py + px * px;
        }
        double theta = (den > 1e-6) ? (num / den) : 0.0;

        double camDx = -meanU * downscale;
        double camDy = -meanV * downscale;

        // --- motion gate ---
        if (Math.hypot(camDx, camDy) > maxMotionPx) {
            res.blocksUsed = n;
            res.processNs = System.nanoTime() - t0;
            return res;
        }

        // --- temporal EMA ---
        if (temporalSmoothing && hasSmooth) {
            camDx = temporalAlpha * camDx + (1.0 - temporalAlpha) * smoothDx;
            camDy = temporalAlpha * camDy + (1.0 - temporalAlpha) * smoothDy;
        }
        smoothDx = camDx; smoothDy = camDy; hasSmooth = true;

        res.valid        = true;
        res.dxPx         = camDx;
        res.dyPx         = camDy;
        res.distancePx   = Math.hypot(camDx, camDy);
        res.directionDeg = normalizeDeg(Math.toDegrees(Math.atan2(camDy, camDx)));
        res.rotationDeg  = -Math.toDegrees(theta);
        res.blocksUsed   = n;
        res.confidence   = (double) n / totalBlocks;
        res.processNs    = System.nanoTime() - t0;

        // фичи для отладки
        int sz = Math.min(n, 256);
        int[] fxs = new int[sz], fys = new int[sz];
        int fc = 0;
        for (int i = 0; i < good && fc < sz; i++) {
            if (Math.abs(dxs[i] - medX) > outlierPx) continue;
            if (Math.abs(dys[i] - medY) > outlierPx) continue;
            int b = idx[i];
            fxs[fc] = (b % nx) * block + block / 2;
            fys[fc] = (b / nx) * block + block / 2;
            fc++;
        }
        res.featureXs = fxs; res.featureYs = fys; res.featureCount = fc;
        return res;
    }

    private static int sadAt(byte[] a, byte[] b, int gw, int gh,
                             int ox, int oy, int dx, int dy, int block) {
        int cy = oy + dy, cx = ox + dx;
        if (cx < 0 || cy < 0 || cx + block > gw || cy + block > gh)
            return Integer.MAX_VALUE / 4;
        int sad = 0;
        for (int ry = 0; ry < block; ry++) {
            int prow = (oy + ry) * gw + ox;
            int crow = (cy + ry) * gw + cx;
            for (int rx = 0; rx < block; rx++)
                sad += Math.abs((a[prow + rx] & 0xFF) - (b[crow + rx] & 0xFF));
        }
        return sad;
    }

    private void swapBuffers() { byte[] t = prev; prev = cur; cur = t; }

    private static double normalizeDeg(double a) {
        a = a % 360.0; if (a < 0) a += 360.0; return a;
    }
}