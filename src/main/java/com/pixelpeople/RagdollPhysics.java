package com.pixelpeople;

/**
 * A small rigid-body ragdoll (position based dynamics, no Minecraft dependencies).
 * Six boxes (head, torso, two arms, two legs) joined by ball joints with cone limits,
 * colliding with solid blocks through their corner points. Units: blocks, seconds.
 */
public final class RagdollPhysics {
    public interface World { boolean solid(int x, int y, int z); }

    public static final double GRAVITY = 25.0;
    public static final int SUBSTEPS = 10;
    public static final double TICK = 0.05;
    private static final double FRICTION = 0.9;
    private static final double LIN_DAMP = 0.35;
    private static final double ANG_DAMP = 1.6;

    public static final class Body {
        public final double[] x = new double[3], xp = new double[3];
        public final double[] q = {0, 0, 0, 1}, qp = {0, 0, 0, 1};
        public final double[] v = new double[3], w = new double[3];
        /** State at the start of the tick, for render interpolation. */
        public final double[] ox = new double[3], oq = {0, 0, 0, 1};
        double invM, invI, mass;
        double[] pts;
    }

    private static final class Joint {
        final int a, b;
        final double[] ra, rb, axA, axB;
        final double maxAngle;
        Joint(int a, int b, double[] ra, double[] rb, double[] axA, double[] axB, double maxAngleDeg) {
            this.a = a; this.b = b; this.ra = ra; this.rb = rb; this.axA = axA; this.axB = axB;
            this.maxAngle = Math.toRadians(maxAngleDeg);
        }
    }

    public final Body[] b = new Body[PixelModel.PARTS];
    private final Joint[] joints = new Joint[5];
    private int calm;
    public boolean asleep;

    public RagdollPhysics() {
        double s = PixelModel.SCALE;
        for (int i = 0; i < b.length; i++) {
            Body bd = new Body();
            double hx = PixelModel.DIM[i][0] * s / 2, hy = PixelModel.DIM[i][1] * s / 2, hz = PixelModel.DIM[i][2] * s / 2;
            double vol = PixelModel.DIM[i][0] * PixelModel.DIM[i][1] * PixelModel.DIM[i][2] / 1000.0;
            bd.mass = vol;
            bd.invM = 1.0 / vol;
            bd.invI = 18.0 / (vol * (4 * (hx * hx + hy * hy + hz * hz)));
            java.util.ArrayList<double[]> pts = new java.util.ArrayList<>();
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sy = -1; sy <= 1; sy += 2)
                    for (int sz = -1; sz <= 1; sz += 2) pts.add(new double[]{sx * hx, sy * hy, sz * hz});
            if (hy > hx * 1.4) { // long parts also get points along their length
                for (int sx = -1; sx <= 1; sx += 2)
                    for (int sz = -1; sz <= 1; sz += 2) pts.add(new double[]{sx * hx, 0, sz * hz});
            }
            bd.pts = new double[pts.size() * 3];
            for (int k = 0; k < pts.size(); k++) System.arraycopy(pts.get(k), 0, bd.pts, k * 3, 3);
            b[i] = bd;
        }
        double[] up = {0, 1, 0}, down = {0, -1, 0};
        int T = PixelModel.BODY;
        joints[0] = joint(T, PixelModel.HEAD, up, up, 60);
        joints[1] = joint(T, PixelModel.R_ARM, down, down, 150);
        joints[2] = joint(T, PixelModel.L_ARM, down, down, 150);
        joints[3] = joint(T, PixelModel.R_LEG, down, down, 100);
        joints[4] = joint(T, PixelModel.L_LEG, down, down, 100);
    }

    private static Joint joint(int parent, int child, double[] axP, double[] axC, double maxDeg) {
        double s = PixelModel.SCALE;
        double[] pv = {PixelModel.PIVOT[child][0], PixelModel.PIVOT[child][1], PixelModel.PIVOT[child][2]};
        double[] ra = new double[3], rb = new double[3];
        for (int k = 0; k < 3; k++) {
            ra[k] = (pv[k] - PixelModel.CEN[parent][k]) * s;
            rb[k] = (pv[k] - PixelModel.CEN[child][k]) * s;
        }
        return new Joint(parent, child, ra, rb, axP, axC, maxDeg);
    }

    // ------------------------------------------------------------------ public control

    public void init(double[][] pos, double[][] rot) {
        for (int i = 0; i < b.length; i++) {
            Body bd = b[i];
            System.arraycopy(pos[i], 0, bd.x, 0, 3);
            System.arraycopy(pos[i], 0, bd.xp, 0, 3);
            System.arraycopy(rot[i], 0, bd.q, 0, 4);
            System.arraycopy(rot[i], 0, bd.qp, 0, 4);
            java.util.Arrays.fill(bd.v, 0);
            java.util.Arrays.fill(bd.w, 0);
        }
        snapshotPrev();
        calm = 0;
        asleep = false;
    }

    public void setAllVelocity(double vx, double vy, double vz) {
        for (Body bd : b) { bd.v[0] = vx; bd.v[1] = vy; bd.v[2] = vz; }
        wake();
    }

    public void snapshotPrev() {
        for (Body bd : b) {
            System.arraycopy(bd.x, 0, bd.ox, 0, 3);
            System.arraycopy(bd.q, 0, bd.oq, 0, 4);
        }
    }

    public void shift(double dx, double dy, double dz) {
        for (Body bd : b) {
            bd.x[0] += dx; bd.x[1] += dy; bd.x[2] += dz;
            bd.xp[0] += dx; bd.xp[1] += dy; bd.xp[2] += dz;
            bd.ox[0] += dx; bd.ox[1] += dy; bd.ox[2] += dz;
        }
    }

    public void wake() { asleep = false; calm = 0; }

    public int nearestBody(double px, double py, double pz) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < b.length; i++) {
            double dx = b[i].x[0] - px, dy = b[i].x[1] - py, dz = b[i].x[2] - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Gives the whole ragdoll a velocity change and kicks the given body at the point (spin). */
    public void hit(int body, double px, double py, double pz, double dvx, double dvy, double dvz, double globalShare) {
        for (Body bd : b) {
            bd.v[0] += dvx * globalShare; bd.v[1] += dvy * globalShare; bd.v[2] += dvz * globalShare;
        }
        Body t = b[body];
        double[] r = {px - t.x[0], py - t.x[1], pz - t.x[2]};
        double[] j = {dvx * t.mass * (1 - globalShare), dvy * t.mass * (1 - globalShare), dvz * t.mass * (1 - globalShare)};
        t.v[0] += j[0] * t.invM; t.v[1] += j[1] * t.invM; t.v[2] += j[2] * t.invM;
        double[] c = cross(r, j);
        t.w[0] += c[0] * t.invI; t.w[1] += c[1] * t.invI; t.w[2] += c[2] * t.invI;
        wake();
    }

    public boolean isFinite() {
        for (Body bd : b) {
            for (double d : bd.x) if (Double.isNaN(d) || Double.isInfinite(d)) return false;
            for (double d : bd.q) if (Double.isNaN(d) || Double.isInfinite(d)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ simulation (one game tick)

    public void step(World world) {
        if (asleep) return;
        double dt = TICK / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) {
            for (Body bd : b) {
                System.arraycopy(bd.x, 0, bd.xp, 0, 3);
                System.arraycopy(bd.q, 0, bd.qp, 0, 4);
                bd.v[1] -= GRAVITY * dt;
                for (int k = 0; k < 3; k++) bd.x[k] += bd.v[k] * dt;
                double[] dq = mul(new double[]{bd.w[0] * 0.5 * dt, bd.w[1] * 0.5 * dt, bd.w[2] * 0.5 * dt, 0}, bd.q);
                for (int k = 0; k < 4; k++) bd.q[k] += dq[k];
                normalize(bd.q);
            }
            for (Joint j : joints) solveJoint(j);
            for (Joint j : joints) solveCone(j);
            for (Body bd : b) solveContacts(bd, world);
            for (Body bd : b) {
                for (int k = 0; k < 3; k++) bd.v[k] = (bd.x[k] - bd.xp[k]) / dt;
                double[] dq = mul(bd.q, new double[]{-bd.qp[0], -bd.qp[1], -bd.qp[2], bd.qp[3]});
                double sg = dq[3] < 0 ? -2.0 : 2.0;
                for (int k = 0; k < 3; k++) bd.w[k] = sg * dq[k] / dt;
                double ld = Math.max(0, 1 - LIN_DAMP * dt), ad = Math.max(0, 1 - ANG_DAMP * dt);
                for (int k = 0; k < 3; k++) { bd.v[k] *= ld; bd.w[k] *= ad; }
            }
        }
        double fast = 0;
        for (Body bd : b) {
            fast = Math.max(fast, Math.sqrt(bd.v[0] * bd.v[0] + bd.v[1] * bd.v[1] + bd.v[2] * bd.v[2]));
            fast = Math.max(fast, 0.3 * Math.sqrt(bd.w[0] * bd.w[0] + bd.w[1] * bd.w[1] + bd.w[2] * bd.w[2]));
        }
        calm = fast < 0.25 ? calm + 1 : 0;
        if (calm >= 30) asleep = true;
    }

    private void solveJoint(Joint j) {
        Body A = b[j.a], B = b[j.b];
        double[] rA = rot(A.q, j.ra), rB = rot(B.q, j.rb);
        double[] d = {B.x[0] + rB[0] - A.x[0] - rA[0], B.x[1] + rB[1] - A.x[1] - rA[1], B.x[2] + rB[2] - A.x[2] - rA[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (len < 1e-9) return;
        double[] n = {d[0] / len, d[1] / len, d[2] / len};
        double w1 = A.invM + A.invI * sq(cross(rA, n)), w2 = B.invM + B.invI * sq(cross(rB, n));
        double lam = len / (w1 + w2);
        double[] p = {n[0] * lam, n[1] * lam, n[2] * lam};
        for (int k = 0; k < 3; k++) { A.x[k] += p[k] * A.invM; B.x[k] -= p[k] * B.invM; }
        double[] pa = cross(rA, p), pb = cross(rB, p);
        applyRot(A, pa[0] * A.invI, pa[1] * A.invI, pa[2] * A.invI);
        applyRot(B, -pb[0] * B.invI, -pb[1] * B.invI, -pb[2] * B.invI);
    }

    private void solveCone(Joint j) {
        Body A = b[j.a], B = b[j.b];
        double[] ua = rot(A.q, j.axA), ub = rot(B.q, j.axB);
        double c = Math.max(-1, Math.min(1, ua[0] * ub[0] + ua[1] * ub[1] + ua[2] * ub[2]));
        double ang = Math.acos(c);
        if (ang <= j.maxAngle) return;
        double[] axis = cross(ub, ua);
        double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (len < 1e-9) return;
        for (int k = 0; k < 3; k++) axis[k] /= len;
        double corr = ang - j.maxAngle;
        double wA = A.invI, wB = B.invI;
        rotateBy(B, axis, corr * wB / (wA + wB));
        rotateBy(A, axis, -corr * wA / (wA + wB));
    }

    private void solveContacts(Body bd, World world) {
        double[] n = new double[3], depth = new double[1];
        for (int k = 0; k < bd.pts.length; k += 3) {
            double[] lp = {bd.pts[k], bd.pts[k + 1], bd.pts[k + 2]};
            double[] r = rot(bd.q, lp);
            double px = bd.x[0] + r[0], py = bd.x[1] + r[1], pz = bd.x[2] + r[2];
            if (!pushOut(world, px, py, pz, n, depth)) continue;
            double w = bd.invM + bd.invI * sq(cross(r, n));
            double dl = depth[0] / w;
            double[] imp = {n[0] * dl, n[1] * dl, n[2] * dl};
            for (int m = 0; m < 3; m++) bd.x[m] += imp[m] * bd.invM;
            double[] c = cross(r, imp);
            applyRot(bd, c[0] * bd.invI, c[1] * bd.invI, c[2] * bd.invI);

            // friction: cancel sliding of the contact point during this substep
            double[] rp = rot(bd.qp, lp), r2 = rot(bd.q, lp);
            double[] dp = {bd.x[0] + r2[0] - bd.xp[0] - rp[0], bd.x[1] + r2[1] - bd.xp[1] - rp[1], bd.x[2] + r2[2] - bd.xp[2] - rp[2]};
            double dn = dp[0] * n[0] + dp[1] * n[1] + dp[2] * n[2];
            double[] t = {dp[0] - n[0] * dn, dp[1] - n[1] * dn, dp[2] - n[2] * dn};
            double tl = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
            if (tl > 1e-9) {
                double f = Math.min(tl, FRICTION * depth[0]);
                double[] tn = {t[0] / tl, t[1] / tl, t[2] / tl};
                double wt = bd.invM + bd.invI * sq(cross(r2, tn));
                double fc = f / wt;
                double[] fi = {-tn[0] * fc, -tn[1] * fc, -tn[2] * fc};
                for (int m = 0; m < 3; m++) bd.x[m] += fi[m] * bd.invM;
                double[] fcv = cross(r2, fi);
                applyRot(bd, fcv[0] * bd.invI, fcv[1] * bd.invI, fcv[2] * bd.invI);
            }
        }
    }

    private static boolean pushOut(World w, double px, double py, double pz, double[] n, double[] depth) {
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
        if (!w.solid(cx, cy, cz)) return false;
        double best = Double.MAX_VALUE;
        double nx = 0, ny = 1, nz = 0;
        if (!w.solid(cx, cy + 1, cz)) { double d = cy + 1 - py; if (d < best) { best = d; nx = 0; ny = 1; nz = 0; } }
        if (!w.solid(cx, cy - 1, cz)) { double d = py - cy; if (d < best) { best = d; nx = 0; ny = -1; nz = 0; } }
        if (!w.solid(cx + 1, cy, cz)) { double d = cx + 1 - px; if (d < best) { best = d; nx = 1; ny = 0; nz = 0; } }
        if (!w.solid(cx - 1, cy, cz)) { double d = px - cx; if (d < best) { best = d; nx = -1; ny = 0; nz = 0; } }
        if (!w.solid(cx, cy, cz + 1)) { double d = cz + 1 - pz; if (d < best) { best = d; nx = 0; ny = 0; nz = 1; } }
        if (!w.solid(cx, cy, cz - 1)) { double d = pz - cz; if (d < best) { best = d; nx = 0; ny = 0; nz = -1; } }
        if (best == Double.MAX_VALUE) { best = cy + 1 - py; nx = 0; ny = 1; nz = 0; }
        n[0] = nx; n[1] = ny; n[2] = nz;
        depth[0] = best;
        return true;
    }

    // ------------------------------------------------------------------ math helpers

    private static void applyRot(Body bd, double rx, double ry, double rz) {
        double[] dq = mul(new double[]{rx * 0.5, ry * 0.5, rz * 0.5, 0}, bd.q);
        for (int k = 0; k < 4; k++) bd.q[k] += dq[k];
        normalize(bd.q);
    }

    private static void rotateBy(Body bd, double[] axis, double angle) {
        double s = Math.sin(angle / 2), c = Math.cos(angle / 2);
        double[] dq = {axis[0] * s, axis[1] * s, axis[2] * s, c};
        double[] r = mul(dq, bd.q);
        System.arraycopy(r, 0, bd.q, 0, 4);
        normalize(bd.q);
    }

    static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    static void normalize(double[] q) {
        double l = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (l < 1e-12) { q[0] = q[1] = q[2] = 0; q[3] = 1; return; }
        for (int i = 0; i < 4; i++) q[i] /= l;
    }

    static double[] rot(double[] q, double[] v) {
        double[] t = cross(new double[]{q[0], q[1], q[2]}, v);
        t[0] *= 2; t[1] *= 2; t[2] *= 2;
        double[] c = cross(new double[]{q[0], q[1], q[2]}, t);
        return new double[]{v[0] + q[3] * t[0] + c[0], v[1] + q[3] * t[1] + c[1], v[2] + q[3] * t[2] + c[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double sq(double[] v) { return v[0] * v[0] + v[1] * v[1] + v[2] * v[2]; }
}
