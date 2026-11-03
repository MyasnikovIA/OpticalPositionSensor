package ru.miacomsoft.optical_position_sensor;

import java.util.Arrays;

public class OpticalFlowTracker {

    public static final class Result {
        public boolean valid;
        public double dxPx, dyPx, distancePx, directionDeg, rotationDeg;
        public int blocksUsed, blocksTotal;
        public double confidence;
        public long processNs;
    }

    private int block = 8;
    private int search = 4;
    private int minContrast = 3;
    private int maxSadPx = 18;
    private int targetWidth = 112;
    private final int outlierPx = 2;

    private int downscale = 2;
    private int gw, gh, nx, ny, totalBlocks;
    private int lastW = -1, lastH = -1;

    private byte[] prev = new byte[0];
    private byte[] cur  = new byte[0];
    private boolean hasPrev;

    private int[] idx = new int[0];
    private int[] dxs = new int[0];
    private int[] dys = new int[0];
    private int[] tmp = new int[0];

    public int getDownscale() { return downscale; }

    public void configure(int targetWidth, int search, int block,
                          int minContrast, int maxSadPx) {
        this.targetWidth = Math.max(32, targetWidth);
        this.search      = Math.max(1, search);
        this.block       = Math.max(4, block);
        this.minContrast = Math.max(0, minContrast);
        this.maxSadPx    = Math.max(1, maxSadPx);
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

        prev = new byte[gw * gh];
        cur  = new byte[gw * gh];
        idx  = new int[totalBlocks];
        dxs  = new int[totalBlocks];
        dys  = new int[totalBlocks];
        tmp  = new int[totalBlocks];

        hasPrev = false;
        lastW = width;
        lastH = height;
    }

    public Result update(byte[] yPlane, int width, int height) {
        long t0 = System.nanoTime();
        Result res = new Result();

        if (width != lastW || height != lastH) reset(width, height);

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

        int good = 0;
        final int maxSad = maxSadPx * block * block;

        for (int by = 0; by < ny; by++) {
            int oy = by * block;
            for (int bx = 0; bx < nx; bx++) {
                int ox = bx * block;

                int sum = 0;
                for (int ry = 0; ry < block; ry++) {
                    int row = (oy + ry) * gw + ox;
                    for (int rx = 0; rx < block; rx++) sum += prev[row + rx] & 0xFF;
                }
                int mean = sum / (block * block);
                int mad = 0;
                for (int ry = 0; ry < block; ry++) {
                    int row = (oy + ry) * gw + ox;
                    for (int rx = 0; rx < block; rx++) {
                        mad += Math.abs((prev[row + rx] & 0xFF) - mean);
                    }
                }
                if (mad / (block * block) < minContrast) continue;

                int bestSad = Integer.MAX_VALUE;
                int bdx = 0, bdy = 0;

                for (int sy = -search; sy <= search; sy++) {
                    int cy = oy + sy;
                    if (cy < 0 || cy + block > gh) continue;
                    for (int sx = -search; sx <= search; sx++) {
                        int cx = ox + sx;
                        if (cx < 0 || cx + block > gw) continue;

                        int sad = 0;
                        for (int ry = 0; ry < block; ry++) {
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

                idx[good] = by * nx + bx;
                dxs[good] = bdx;
                dys[good] = bdy;
                good++;
            }
        }

        swapBuffers();

        res.blocksTotal = totalBlocks;
        res.blocksUsed  = good;

        int minGood = Math.max(6, totalBlocks / 12);
        if (good < minGood) {
            res.processNs = System.nanoTime() - t0;
            return res;
        }

        System.arraycopy(dxs, 0, tmp, 0, good);
        Arrays.sort(tmp, 0, good);
        int medX = tmp[good / 2];
        System.arraycopy(dys, 0, tmp, 0, good);
        Arrays.sort(tmp, 0, good);
        int medY = tmp[good / 2];

        double sumU = 0, sumV = 0;
        int n = 0;
        for (int i = 0; i < good; i++) {
            if (Math.abs(dxs[i] - medX) > outlierPx) continue;
            if (Math.abs(dys[i] - medY) > outlierPx) continue;
            sumU += dxs[i];
            sumV += dys[i];
            n++;
        }
        if (n < 4) { res.blocksUsed = n; res.processNs = System.nanoTime() - t0; return res; }
        double meanU = sumU / n;
        double meanV = sumV / n;

        double cx = (gw - 1) * 0.5;
        double cy = (gh - 1) * 0.5;
        double num = 0, den = 0;
        for (int i = 0; i < good; i++) {
            if (Math.abs(dxs[i] - medX) > outlierPx) continue;
            if (Math.abs(dys[i] - medY) > outlierPx) continue;
            int b = idx[i];
            double px = (b % nx) * block + block * 0.5 - cx;
            double py = (b / nx) * block + block * 0.5 - cy;
            num += (dxs[i] - meanU) * (-py) + (dys[i] - meanV) * px;
            den += py * py + px * px;
        }
        double theta = (den > 1e-6) ? (num / den) : 0.0;

        double camDx = -meanU * downscale;
        double camDy = -meanV * downscale;

        res.valid        = true;
        res.dxPx         = camDx;
        res.dyPx         = camDy;
        res.distancePx   = Math.hypot(camDx, camDy);
        res.directionDeg = normalizeDeg(Math.toDegrees(Math.atan2(camDy, camDx)));
        res.rotationDeg  = -Math.toDegrees(theta);
        res.blocksUsed   = n;
        res.confidence   = (double) n / totalBlocks;
        res.processNs    = System.nanoTime() - t0;
        return res;
    }

    private void swapBuffers() { byte[] t = prev; prev = cur; cur = t; }

    private static double normalizeDeg(double a) {
        a = a % 360.0;
        if (a < 0) a += 360.0;
        return a;
    }
}