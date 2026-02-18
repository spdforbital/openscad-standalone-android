package com.openscad.standalone;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class StlParser {

    static StlModel parse(File file) throws IOException {
        byte[] data = readAll(file);
        if (looksBinary(data)) {
            return parseBinary(data);
        }
        return parseAscii(data);
    }

    private static StlModel parseBinary(byte[] data) throws IOException {
        if (data.length < 84) {
            throw new IOException("STL too small");
        }

        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(80);
        long triCountLong = bb.getInt() & 0xffffffffL;
        if (triCountLong <= 0 || triCountLong > Integer.MAX_VALUE) {
            throw new IOException("Invalid STL triangle count: " + triCountLong);
        }

        int triCount = (int) triCountLong;
        long expected = 84L + (long) triCount * 50L;
        if (expected > data.length) {
            throw new IOException("Binary STL truncated");
        }

        float[] vertices = new float[triCount * 9];
        float[] normals = new float[triCount * 9];

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        int vIndex = 0;
        for (int i = 0; i < triCount; i++) {
            float nx = bb.getFloat();
            float ny = bb.getFloat();
            float nz = bb.getFloat();

            float x1 = bb.getFloat();
            float y1 = bb.getFloat();
            float z1 = bb.getFloat();
            float x2 = bb.getFloat();
            float y2 = bb.getFloat();
            float z2 = bb.getFloat();
            float x3 = bb.getFloat();
            float y3 = bb.getFloat();
            float z3 = bb.getFloat();

            bb.getShort();

            vertices[vIndex] = x1;
            vertices[vIndex + 1] = y1;
            vertices[vIndex + 2] = z1;
            vertices[vIndex + 3] = x2;
            vertices[vIndex + 4] = y2;
            vertices[vIndex + 5] = z2;
            vertices[vIndex + 6] = x3;
            vertices[vIndex + 7] = y3;
            vertices[vIndex + 8] = z3;

            if (isZeroVector(nx, ny, nz)) {
                float ux = x2 - x1;
                float uy = y2 - y1;
                float uz = z2 - z1;
                float vx = x3 - x1;
                float vy = y3 - y1;
                float vz = z3 - z1;
                nx = uy * vz - uz * vy;
                ny = uz * vx - ux * vz;
                nz = ux * vy - uy * vx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-8f) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                } else {
                    nx = 0f;
                    ny = 0f;
                    nz = 1f;
                }
            }

            for (int n = 0; n < 3; n++) {
                normals[vIndex + n * 3] = nx;
                normals[vIndex + n * 3 + 1] = ny;
                normals[vIndex + n * 3 + 2] = nz;
            }

            minX = min(minX, x1, x2, x3);
            minY = min(minY, y1, y2, y3);
            minZ = min(minZ, z1, z2, z3);
            maxX = max(maxX, x1, x2, x3);
            maxY = max(maxY, y1, y2, y3);
            maxZ = max(maxZ, z1, z2, z3);

            vIndex += 9;
        }

        return buildModel(vertices, normals, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static StlModel parseAscii(byte[] data) throws IOException {
        String text = new String(data, StandardCharsets.UTF_8);
        String[] lines = text.split("\\r?\\n");

        List<Float> verts = new ArrayList<Float>();
        List<Float> norms = new ArrayList<Float>();

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        float nx = 0f;
        float ny = 0f;
        float nz = 1f;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("facet normal")) {
                String[] p = line.split("\\s+");
                if (p.length >= 5) {
                    nx = parseFloatSafe(p[2]);
                    ny = parseFloatSafe(p[3]);
                    nz = parseFloatSafe(p[4]);
                }
            } else if (line.startsWith("vertex")) {
                String[] p = line.split("\\s+");
                if (p.length >= 4) {
                    float x = parseFloatSafe(p[1]);
                    float y = parseFloatSafe(p[2]);
                    float z = parseFloatSafe(p[3]);

                    verts.add(x);
                    verts.add(y);
                    verts.add(z);
                    norms.add(nx);
                    norms.add(ny);
                    norms.add(nz);

                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }

        if (verts.isEmpty()) {
            throw new IOException("ASCII STL has no vertices");
        }

        float[] vertices = new float[verts.size()];
        float[] normals = new float[norms.size()];
        for (int i = 0; i < verts.size(); i++) {
            vertices[i] = verts.get(i);
            normals[i] = norms.get(i);
        }

        return buildModel(vertices, normals, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static StlModel buildModel(
        float[] vertices,
        float[] normals,
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ
    ) throws IOException {
        if (vertices.length == 0) {
            throw new IOException("Empty STL mesh");
        }

        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;

        float sx = maxX - minX;
        float sy = maxY - minY;
        float sz = maxZ - minZ;
        float radius = Math.max(sx, Math.max(sy, sz)) * 0.6f;
        if (radius < 0.001f) {
            radius = 1f;
        }

        return new StlModel(vertices, normals, cx, cy, cz, radius);
    }

    private static byte[] readAll(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("File not found: " + file);
        }
        long len = file.length();
        if (len <= 0 || len > Integer.MAX_VALUE) {
            throw new IOException("Invalid STL file size");
        }

        byte[] data = new byte[(int) len];
        FileInputStream in = new FileInputStream(file);
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        in.close();

        if (offset != data.length) {
            throw new IOException("Could not read STL fully");
        }
        return data;
    }

    private static boolean looksBinary(byte[] data) {
        if (data.length < 84) {
            return false;
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        bb.position(80);
        long triCount = bb.getInt() & 0xffffffffL;
        long expected = 84L + triCount * 50L;
        if (expected == data.length) {
            return true;
        }

        String header = new String(data, 0, Math.min(80, data.length), StandardCharsets.US_ASCII).trim();
        return !header.startsWith("solid");
    }

    private static boolean isZeroVector(float x, float y, float z) {
        return Math.abs(x) < 1e-8f && Math.abs(y) < 1e-8f && Math.abs(z) < 1e-8f;
    }

    private static float parseFloatSafe(String text) {
        try {
            return Float.parseFloat(text);
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private static float min(float current, float a, float b, float c) {
        return Math.min(current, Math.min(a, Math.min(b, c)));
    }

    private static float max(float current, float a, float b, float c) {
        return Math.max(current, Math.max(a, Math.max(b, c)));
    }
}
