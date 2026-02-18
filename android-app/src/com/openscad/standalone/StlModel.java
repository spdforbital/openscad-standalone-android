package com.openscad.standalone;

class StlModel {
    final float[] vertices;
    final float[] normals;
    final int vertexCount;

    final float centerX;
    final float centerY;
    final float centerZ;
    final float radius;

    StlModel(float[] vertices, float[] normals, float centerX, float centerY, float centerZ, float radius) {
        this.vertices = vertices;
        this.normals = normals;
        this.vertexCount = vertices.length / 3;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
    }
}
