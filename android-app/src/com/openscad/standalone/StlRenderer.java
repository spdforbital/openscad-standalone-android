package com.openscad.standalone;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

class StlRenderer implements GLSurfaceView.Renderer {

    private static final String SHADED_VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uModelMatrix;\n" +
        "attribute vec3 aPosition;\n" +
        "attribute vec3 aNormal;\n" +
        "varying vec3 vNormal;\n" +
        "void main() {\n" +
        "  vNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);\n" +
        "  gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
        "}\n";

    private static final String SHADED_FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "varying vec3 vNormal;\n" +
        "uniform vec3 uLightDir;\n" +
        "uniform vec4 uColor;\n" +
        "void main() {\n" +
        "  float diff = max(dot(normalize(vNormal), normalize(uLightDir)), 0.0);\n" +
        "  float lighting = 0.25 + diff * 0.75;\n" +
        "  gl_FragColor = vec4(uColor.rgb * lighting, uColor.a);\n" +
        "}\n";

    private static final String LINE_VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n" +
        "attribute vec3 aPosition;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
        "}\n";

    private static final String LINE_FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "uniform vec4 uColor;\n" +
        "void main() {\n" +
        "  gl_FragColor = uColor;\n" +
        "}\n";

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];
    private final float[] vp = new float[16];
    private final float[] axisMvp = new float[16];

    private FloatBuffer vertexBuffer;
    private FloatBuffer normalBuffer;
    private FloatBuffer lineVertexBuffer;
    private FloatBuffer axisVertexBuffer;
    private int vertexCount;
    private int lineVertexCount;
    private int axisVertexCount;

    private float modelCenterX;
    private float modelCenterY;
    private float modelCenterZ;
    private float modelRadius = 1f;

    private float yawDeg = 45f;
    private float pitchDeg = 25f;
    private float distance = 6f;
    private float targetX;
    private float targetY;
    private float targetZ;

    private boolean wireframeMode;

    private int shadedProgram;
    private int shadedAPosition;
    private int shadedANormal;
    private int shadedUMvpMatrix;
    private int shadedUModelMatrix;
    private int shadedULightDir;
    private int shadedUColor;

    private int lineProgram;
    private int lineAPosition;
    private int lineUMvpMatrix;
    private int lineUColor;

    private StlModel pendingModel;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.07f, 0.07f, 0.11f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        shadedProgram = buildProgram(SHADED_VERTEX_SHADER, SHADED_FRAGMENT_SHADER);
        shadedAPosition = GLES20.glGetAttribLocation(shadedProgram, "aPosition");
        shadedANormal = GLES20.glGetAttribLocation(shadedProgram, "aNormal");
        shadedUMvpMatrix = GLES20.glGetUniformLocation(shadedProgram, "uMVPMatrix");
        shadedUModelMatrix = GLES20.glGetUniformLocation(shadedProgram, "uModelMatrix");
        shadedULightDir = GLES20.glGetUniformLocation(shadedProgram, "uLightDir");
        shadedUColor = GLES20.glGetUniformLocation(shadedProgram, "uColor");

        lineProgram = buildProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER);
        lineAPosition = GLES20.glGetAttribLocation(lineProgram, "aPosition");
        lineUMvpMatrix = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix");
        lineUColor = GLES20.glGetUniformLocation(lineProgram, "uColor");

        float[] axis = new float[] {
            0f, 0f, 0f, 2f, 0f, 0f,
            0f, 0f, 0f, 0f, 2f, 0f,
            0f, 0f, 0f, 0f, 0f, 2f
        };
        axisVertexBuffer = allocateFloatBuffer(axis);
        axisVertexCount = axis.length / 3;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = width > 0 && height > 0 ? ((float) width) / ((float) height) : 1f;
        Matrix.perspectiveM(projection, 0, 45f, aspect, 0.1f, 10000f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        applyPendingModel();

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (vertexBuffer == null || normalBuffer == null || vertexCount <= 0) {
            return;
        }

        updateMatrices();

        if (wireframeMode) {
            drawWireframe();
        } else {
            drawShaded();
        }
        drawAxes();
    }

    void setModel(StlModel model) {
        synchronized (this) {
            pendingModel = model;
        }
    }

    void setWireframeMode(boolean enabled) {
        wireframeMode = enabled;
    }

    void resetView() {
        yawDeg = 45f;
        pitchDeg = 25f;
        distance = 6f;
        targetX = 0f;
        targetY = 0f;
        targetZ = 0f;
    }

    void addRotation(float dxDeg, float dyDeg) {
        yawDeg += dxDeg;
        pitchDeg += dyDeg;
        if (pitchDeg > 89f) {
            pitchDeg = 89f;
        }
        if (pitchDeg < -89f) {
            pitchDeg = -89f;
        }
    }

    void addPan(float dxPixels, float dyPixels) {
        float sensitivity = 0.0025f * distance;
        targetX -= dxPixels * sensitivity;
        targetY += dyPixels * sensitivity;
    }

    void zoomBy(float factor) {
        if (factor <= 0f) {
            return;
        }
        distance /= factor;
        if (distance < 1.2f) {
            distance = 1.2f;
        }
        if (distance > 50f) {
            distance = 50f;
        }
    }

    private void updateMatrices() {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);

        float eyeX = targetX + (float) (Math.cos(pitch) * Math.sin(yaw)) * distance;
        float eyeY = targetY + (float) Math.sin(pitch) * distance;
        float eyeZ = targetZ + (float) (Math.cos(pitch) * Math.cos(yaw)) * distance;

        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0f, 1f, 0f);

        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, -modelCenterX, -modelCenterY, -modelCenterZ);

        Matrix.multiplyMM(vp, 0, projection, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        Matrix.multiplyMM(axisMvp, 0, projection, 0, view, 0);
    }

    private void drawShaded() {
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(shadedProgram);

        vertexBuffer.position(0);
        normalBuffer.position(0);

        GLES20.glEnableVertexAttribArray(shadedAPosition);
        GLES20.glVertexAttribPointer(shadedAPosition, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

        GLES20.glEnableVertexAttribArray(shadedANormal);
        GLES20.glVertexAttribPointer(shadedANormal, 3, GLES20.GL_FLOAT, false, 12, normalBuffer);

        GLES20.glUniformMatrix4fv(shadedUMvpMatrix, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(shadedUModelMatrix, 1, false, model, 0);
        GLES20.glUniform3f(shadedULightDir, 0.5f, 0.9f, 0.2f);
        GLES20.glUniform4f(shadedUColor, 0.54f, 0.71f, 0.98f, 1f);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

        GLES20.glDisableVertexAttribArray(shadedAPosition);
        GLES20.glDisableVertexAttribArray(shadedANormal);
    }

    private void drawWireframe() {
        if (lineVertexBuffer == null || lineVertexCount <= 0) {
            return;
        }

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(lineProgram);

        lineVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(lineAPosition);
        GLES20.glVertexAttribPointer(lineAPosition, 3, GLES20.GL_FLOAT, false, 12, lineVertexBuffer);

        GLES20.glUniformMatrix4fv(lineUMvpMatrix, 1, false, mvp, 0);
        GLES20.glUniform4f(lineUColor, 0.75f, 0.86f, 0.98f, 1f);

        GLES20.glLineWidth(1.2f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVertexCount);

        GLES20.glDisableVertexAttribArray(lineAPosition);
    }

    private void drawAxes() {
        if (axisVertexBuffer == null || axisVertexCount <= 0) {
            return;
        }

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(lineProgram);
        GLES20.glLineWidth(2.5f);

        axisVertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(lineAPosition);
        GLES20.glVertexAttribPointer(lineAPosition, 3, GLES20.GL_FLOAT, false, 12, axisVertexBuffer);

        GLES20.glUniformMatrix4fv(lineUMvpMatrix, 1, false, axisMvp, 0);

        GLES20.glUniform4f(lineUColor, 0.95f, 0.42f, 0.42f, 1f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2);
        GLES20.glUniform4f(lineUColor, 0.45f, 0.95f, 0.55f, 1f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 2, 2);
        GLES20.glUniform4f(lineUColor, 0.45f, 0.65f, 0.98f, 1f);
        GLES20.glDrawArrays(GLES20.GL_LINES, 4, 2);

        GLES20.glDisableVertexAttribArray(lineAPosition);
    }

    private void applyPendingModel() {
        StlModel modelRef;
        synchronized (this) {
            modelRef = pendingModel;
            pendingModel = null;
        }

        if (modelRef == null) {
            return;
        }

        vertexBuffer = allocateFloatBuffer(modelRef.vertices);
        normalBuffer = allocateFloatBuffer(modelRef.normals);
        vertexCount = modelRef.vertexCount;

        float[] lineVerts = buildEdgeLines(modelRef.vertices);
        lineVertexBuffer = allocateFloatBuffer(lineVerts);
        lineVertexCount = lineVerts.length / 3;

        modelCenterX = modelRef.centerX;
        modelCenterY = modelRef.centerY;
        modelCenterZ = modelRef.centerZ;
        modelRadius = modelRef.radius;

        resetView();
        distance = modelRadius * 2.8f;
        if (distance < 6f) {
            distance = 6f;
        }
        if (distance > 2000f) {
            distance = 2000f;
        }
    }

    private static float[] buildEdgeLines(float[] triVertices) {
        int triCount = triVertices.length / 9;
        float[] lines = new float[triCount * 18];
        int out = 0;

        for (int i = 0; i < triVertices.length; i += 9) {
            float ax = triVertices[i];
            float ay = triVertices[i + 1];
            float az = triVertices[i + 2];
            float bx = triVertices[i + 3];
            float by = triVertices[i + 4];
            float bz = triVertices[i + 5];
            float cx = triVertices[i + 6];
            float cy = triVertices[i + 7];
            float cz = triVertices[i + 8];

            lines[out++] = ax;
            lines[out++] = ay;
            lines[out++] = az;
            lines[out++] = bx;
            lines[out++] = by;
            lines[out++] = bz;

            lines[out++] = bx;
            lines[out++] = by;
            lines[out++] = bz;
            lines[out++] = cx;
            lines[out++] = cy;
            lines[out++] = cz;

            lines[out++] = cx;
            lines[out++] = cy;
            lines[out++] = cz;
            lines[out++] = ax;
            lines[out++] = ay;
            lines[out++] = az;
        }

        return lines;
    }

    private static FloatBuffer allocateFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int buildProgram(String vertexSrc, String fragmentSrc) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);

        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vertex);
        GLES20.glAttachShader(p, fragment);
        GLES20.glLinkProgram(p);

        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("GL program link failed: " + log);
        }

        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return p;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("GL shader compile failed: " + log);
        }
        return shader;
    }
}
