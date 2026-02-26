package com.openscad.standalone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

class StlGlSurfaceView extends View {
    enum ViewPreset {
        ISO,
        POS_X,
        NEG_X,
        POS_Y,
        NEG_Y,
        POS_Z,
        NEG_Z
    }

    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 6.0f;
    private static final int VIEW_BACKGROUND_COLOR = 0xFF11111B;

    private final ScaleGestureDetector scaleDetector;
    private final Paint shadedPaint;
    private final Paint wirePaint;
    private final Paint axisXPaint;
    private final Paint axisYPaint;
    private final Paint axisZPaint;

    private final Path triPath = new Path();

    private StlModel model;
    private boolean wireframeMode;
    private boolean axisLinesVisible = true;

    private float yawDeg = 45f;
    private float pitchDeg = 25f;
    private float panX;
    private float panY;
    private float zoom = 1f;

    private float lastX;
    private float lastY;
    private float lastMidX;
    private float lastMidY;

    private float[] sx = new float[0];
    private float[] sy = new float[0];
    private float[] sz = new float[0];
    private float[] tx = new float[0];
    private float[] ty = new float[0];
    private float[] tz = new float[0];
    private boolean[] visible = new boolean[0];
    private int[] triOrder = new int[0];
    private float[] triDepth = new float[0];

    StlGlSurfaceView(Context context) {
        super(context);
        scaleDetector = createScaleDetector(context);
        shadedPaint = makePaint(true, 0xFF89B4FA, 1.0f);
        wirePaint = makePaint(false, 0xFFBFDDFC, 2.0f);
        axisXPaint = makePaint(false, 0xFFF45A5A, 3.0f);
        axisYPaint = makePaint(false, 0xFF62ED7A, 3.0f);
        axisZPaint = makePaint(false, 0xFF5D94FA, 3.0f);
        init();
    }

    StlGlSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = createScaleDetector(context);
        shadedPaint = makePaint(true, 0xFF89B4FA, 1.0f);
        wirePaint = makePaint(false, 0xFFBFDDFC, 2.0f);
        axisXPaint = makePaint(false, 0xFFF45A5A, 3.0f);
        axisYPaint = makePaint(false, 0xFF62ED7A, 3.0f);
        axisZPaint = makePaint(false, 0xFF5D94FA, 3.0f);
        init();
    }

    private void init() {
        setBackgroundColor(VIEW_BACKGROUND_COLOR);
        setFocusable(true);
        setClickable(true);
    }

    private static Paint makePaint(boolean fill, int color, float strokeWidth) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(strokeWidth);
        return p;
    }

    private ScaleGestureDetector createScaleDetector(Context context) {
        return new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoom *= detector.getScaleFactor();
                if (zoom < MIN_ZOOM) {
                    zoom = MIN_ZOOM;
                }
                if (zoom > MAX_ZOOM) {
                    zoom = MAX_ZOOM;
                }
                invalidate();
                return true;
            }
        });
    }

    void setModel(StlModel model) {
        this.model = model;
        resetCamera();
        invalidate();
    }

    void resetCamera() {
        yawDeg = 45f;
        pitchDeg = 25f;
        panX = 0f;
        panY = 0f;
        zoom = 1f;
        invalidate();
    }

    void setWireframeMode(boolean wireframe) {
        wireframeMode = wireframe;
        invalidate();
    }

    void setAxisLinesVisible(boolean visible) {
        axisLinesVisible = visible;
        invalidate();
    }

    void setViewPreset(ViewPreset preset) {
        panX = 0f;
        panY = 0f;
        switch (preset) {
            case POS_X:
                yawDeg = 90f;
                pitchDeg = 0f;
                break;
            case NEG_X:
                yawDeg = -90f;
                pitchDeg = 0f;
                break;
            case POS_Y:
                yawDeg = 0f;
                pitchDeg = 89f;
                break;
            case NEG_Y:
                yawDeg = 0f;
                pitchDeg = -89f;
                break;
            case POS_Z:
                yawDeg = 0f;
                pitchDeg = 0f;
                break;
            case NEG_Z:
                yawDeg = 180f;
                pitchDeg = 0f;
                break;
            case ISO:
            default:
                yawDeg = 45f;
                pitchDeg = 25f;
                break;
        }
        invalidate();
    }

    void onResume() {
        // no-op for software renderer
    }

    void onPause() {
        // no-op for software renderer
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(VIEW_BACKGROUND_COLOR);

        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 2) {
            return;
        }

        if (axisLinesVisible) {
            drawAxes(canvas, w, h);
        }
        if (model == null || model.vertexCount <= 0) {
            return;
        }

        renderModel(canvas, w, h);
    }

    private void drawAxes(Canvas canvas, int w, int h) {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float focal = Math.min(w, h) * 0.65f;
        float camDist = 4.5f / zoom;

        float o0x = projectAxisX(0f, 0f, 0f, cosY, sinY, cosP, sinP, cx, focal, camDist);
        float o0y = projectAxisY(0f, 0f, 0f, cosY, sinY, cosP, sinP, cy, focal, camDist);

        float pxX = projectAxisX(1.8f, 0f, 0f, cosY, sinY, cosP, sinP, cx, focal, camDist);
        float pxY = projectAxisY(1.8f, 0f, 0f, cosY, sinY, cosP, sinP, cy, focal, camDist);

        float pyX = projectAxisX(0f, 1.8f, 0f, cosY, sinY, cosP, sinP, cx, focal, camDist);
        float pyY = projectAxisY(0f, 1.8f, 0f, cosY, sinY, cosP, sinP, cy, focal, camDist);

        float pzX = projectAxisX(0f, 0f, 1.8f, cosY, sinY, cosP, sinP, cx, focal, camDist);
        float pzY = projectAxisY(0f, 0f, 1.8f, cosY, sinY, cosP, sinP, cy, focal, camDist);

        if (!Float.isNaN(o0x)) {
            if (!Float.isNaN(pxX))
                canvas.drawLine(o0x, o0y, pxX, pxY, axisXPaint);
            if (!Float.isNaN(pyX))
                canvas.drawLine(o0x, o0y, pyX, pyY, axisYPaint);
            if (!Float.isNaN(pzX))
                canvas.drawLine(o0x, o0y, pzX, pzY, axisZPaint);
        }
    }

    private float projectAxisX(float x, float y, float z,
            float cosY, float sinY, float cosP, float sinP,
            float cx, float focal, float camDist) {
        float x1 = x * cosY + z * sinY + panX;
        float z1 = -x * sinY + z * cosY;
        float z2 = y * sinP + z1 * cosP;
        float depth = z2 + camDist;
        if (depth <= 0.05f)
            return Float.NaN;
        return cx + x1 * focal / depth;
    }

    private float projectAxisY(float x, float y, float z,
            float cosY, float sinY, float cosP, float sinP,
            float cy, float focal, float camDist) {
        float z1 = -x * sinY + z * cosY;
        float y1 = y * cosP - z1 * sinP + panY;
        float z2 = y * sinP + z1 * cosP;
        float depth = z2 + camDist;
        if (depth <= 0.05f)
            return Float.NaN;
        return cy - y1 * focal / depth;
    }

    private void renderModel(Canvas canvas, int w, int h) {
        int vertexCount = model.vertexCount;
        int triCount = vertexCount / 3;
        ensureCapacity(vertexCount, triCount);

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float focal = Math.min(w, h) * 0.65f;

        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);

        float camDistance = 4.5f / zoom;
        float invRadius = 1.0f / Math.max(model.radius, 0.001f);

        for (int i = 0; i < vertexCount; i++) {
            int k = i * 3;
            float x = (model.vertices[k] - model.centerX) * invRadius;
            float y = (model.vertices[k + 1] - model.centerY) * invRadius;
            float z = (model.vertices[k + 2] - model.centerZ) * invRadius;

            float x1 = x * cosY + z * sinY;
            float z1 = -x * sinY + z * cosY;
            float y1 = y * cosP - z1 * sinP;
            float z2 = y * sinP + z1 * cosP;

            x1 += panX;
            y1 += panY;

            tx[i] = x1;
            ty[i] = y1;
            tz[i] = z2;

            float depth = z2 + camDistance;
            sz[i] = depth;
            if (depth <= 0.05f) {
                visible[i] = false;
                continue;
            }

            visible[i] = true;
            sx[i] = cx + x1 * focal / depth;
            sy[i] = cy - y1 * focal / depth;
        }

        if (wireframeMode) {
            drawWireframe(canvas, vertexCount);
        } else {
            drawShaded(canvas, vertexCount);
        }
    }

    private void drawWireframe(Canvas canvas, int vertexCount) {
        for (int i = 0; i + 2 < vertexCount; i += 3) {
            if (!visible[i] || !visible[i + 1] || !visible[i + 2]) {
                continue;
            }
            canvas.drawLine(sx[i], sy[i], sx[i + 1], sy[i + 1], wirePaint);
            canvas.drawLine(sx[i + 1], sy[i + 1], sx[i + 2], sy[i + 2], wirePaint);
            canvas.drawLine(sx[i + 2], sy[i + 2], sx[i], sy[i], wirePaint);
        }
    }

    private void drawShaded(Canvas canvas, int vertexCount) {
        float lx = 0.45f;
        float ly = 0.75f;
        float lz = 0.48f;
        int triCount = vertexCount / 3;
        int visibleTriCount = 0;

        for (int t = 0; t < triCount; t++) {
            int i = t * 3;
            if (!visible[i] || !visible[i + 1] || !visible[i + 2]) {
                continue;
            }
            triOrder[visibleTriCount] = i;
            triDepth[visibleTriCount] = (sz[i] + sz[i + 1] + sz[i + 2]) * 0.3333333f;
            visibleTriCount++;
        }

        if (visibleTriCount == 0) {
            return;
        }

        sortTrianglesBackToFront(0, visibleTriCount - 1);

        for (int t = 0; t < visibleTriCount; t++) {
            int i = triOrder[t];

            float ux = tx[i + 1] - tx[i];
            float uy = ty[i + 1] - ty[i];
            float uz = tz[i + 1] - tz[i];
            float vx = tx[i + 2] - tx[i];
            float vy = ty[i + 2] - ty[i];
            float vz = tz[i + 2] - tz[i];

            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;

            float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen < 1e-6f) {
                continue;
            }

            nx /= nLen;
            ny /= nLen;
            nz /= nLen;

            float lit = Math.abs(nx * lx + ny * ly + nz * lz);
            float light = 0.45f + 0.55f * lit;

            int r = clamp((int) (0x89 * light));
            int g = clamp((int) (0xB4 * light));
            int b = clamp((int) (0xFA * light));
            shadedPaint.setColor(Color.rgb(r, g, b));

            triPath.reset();
            triPath.moveTo(sx[i], sy[i]);
            triPath.lineTo(sx[i + 1], sy[i + 1]);
            triPath.lineTo(sx[i + 2], sy[i + 2]);
            triPath.close();
            canvas.drawPath(triPath, shadedPaint);
        }
    }

    private void sortTrianglesBackToFront(int left, int right) {
        int i = left;
        int j = right;
        float pivot = triDepth[(left + right) >>> 1];

        while (i <= j) {
            while (triDepth[i] > pivot) {
                i++;
            }
            while (triDepth[j] < pivot) {
                j--;
            }
            if (i <= j) {
                float d = triDepth[i];
                triDepth[i] = triDepth[j];
                triDepth[j] = d;

                int idx = triOrder[i];
                triOrder[i] = triOrder[j];
                triOrder[j] = idx;

                i++;
                j--;
            }
        }

        if (left < j) {
            sortTrianglesBackToFront(left, j);
        }
        if (i < right) {
            sortTrianglesBackToFront(i, right);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    lastMidX = midpointX(event);
                    lastMidY = midpointY(event);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    float midX = midpointX(event);
                    float midY = midpointY(event);
                    float dx = midX - lastMidX;
                    float dy = midY - lastMidY;
                    lastMidX = midX;
                    lastMidY = midY;

                    float w = Math.max(getWidth(), 1);
                    float h = Math.max(getHeight(), 1);
                    panX += (dx / w) * (2.6f / zoom);
                    panY -= (dy / h) * (2.6f / zoom);
                    invalidate();
                    return true;
                }

                if (event.getPointerCount() == 1) {
                    float x = event.getX();
                    float y = event.getY();

                    if (scaleDetector.isInProgress()) {
                        lastX = x;
                        lastY = y;
                        return true;
                    }

                    float dx = x - lastX;
                    float dy = y - lastY;
                    lastX = x;
                    lastY = y;

                    yawDeg += dx * 0.35f;
                    pitchDeg += dy * 0.35f;
                    if (pitchDeg > 89f) {
                        pitchDeg = 89f;
                    }
                    if (pitchDeg < -89f) {
                        pitchDeg = -89f;
                    }
                    invalidate();
                    return true;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                int liftedIndex = event.getActionIndex();
                int remainingIndex = firstRemainingPointerIndex(event, liftedIndex);
                if (remainingIndex >= 0) {
                    lastX = event.getX(remainingIndex);
                    lastY = event.getY(remainingIndex);
                }
                lastMidX = midpointX(event);
                lastMidY = midpointY(event);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void ensureCapacity(int vertexCount, int triCount) {
        if (sx.length >= vertexCount) {
            if (triOrder.length >= triCount) {
                return;
            }
        } else {
            sx = new float[vertexCount];
            sy = new float[vertexCount];
            sz = new float[vertexCount];
            tx = new float[vertexCount];
            ty = new float[vertexCount];
            tz = new float[vertexCount];
            visible = new boolean[vertexCount];
        }

        triOrder = new int[triCount];
        triDepth = new float[triCount];
    }

    private static int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }

    private static float midpointX(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return event.getX();
        }
        return (event.getX(0) + event.getX(1)) * 0.5f;
    }

    private static float midpointY(MotionEvent event) {
        if (event.getPointerCount() < 2) {
            return event.getY();
        }
        return (event.getY(0) + event.getY(1)) * 0.5f;
    }

    private static int firstRemainingPointerIndex(MotionEvent event, int liftedIndex) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i != liftedIndex) {
                return i;
            }
        }
        return -1;
    }

}
