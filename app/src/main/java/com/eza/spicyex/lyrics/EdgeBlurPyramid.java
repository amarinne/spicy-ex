package com.eza.spicyex.lyrics;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * One progressive blur band, edge at y = 0. Seven Gaussian pyramid levels are recorded as
 * RenderNodes, packed side by side into an atlas, and sampled by AGSL: each pixel picks the level
 * for its radius and interpolates the two surrounding levels with four bilinear B-spline taps.
 * All downsampling, blurring, and sampling run in HWUI. Ported from the blur lab on branch
 * feature/edge-blur (experiments/blur-lab).
 */
@android.annotation.TargetApi(33)
final class EdgeBlurPyramid {
    private static final int LEVELS = 7;
    /** Full radius from the edge to this fraction of the band, then a smoothstep to sharp. */
    static final float FULL_FRACTION = 0.58f;

    private static final String CODE =
        "uniform shader atlas; uniform float H; uniform float radius;\n"+
        "uniform float offsets[7]; uniform float widths[7]; uniform float heights[7];\n"+
        "float off(int n){if(n==0)return offsets[0];if(n==1)return offsets[1];if(n==2)return offsets[2];if(n==3)return offsets[3];if(n==4)return offsets[4];if(n==5)return offsets[5];return offsets[6];}\n"+
        "float wid(int n){if(n==0)return widths[0];if(n==1)return widths[1];if(n==2)return widths[2];if(n==3)return widths[3];if(n==4)return widths[4];if(n==5)return widths[5];return widths[6];}\n"+
        "float hei(int n){if(n==0)return heights[0];if(n==1)return heights[1];if(n==2)return heights[2];if(n==3)return heights[3];if(n==4)return heights[4];if(n==5)return heights[5];return heights[6];}\n"+
        "half4 tap(float2 p,int l){return atlas.eval(float2(off(l),0)+clamp(p,float2(.5),float2(wid(l)-.5,hei(l)-.5)));}\n"+
        "half4 level(float2 p,int l){\n"+
        " if(l==0)return tap(p,0);\n"+
        " float2 d=p*exp2(-float(l))-.5; float2 c=floor(d);float2 x=c-d+1.;float2 X=d-c;float2 x3=x*x*x;\n"+
        " float2 coeff=.5*x*x+.5*x+.16666667;float2 w1=-x3/3.+coeff;float2 w2=1.-w1;\n"+
        " float2 o1=(-.5*x3+coeff)/w1+c-.5;float2 o2=(X*X*X/6.)/w2+c+1.5;\n"+
        " return half(w1.x*w1.y)*tap(float2(o1.x,o1.y),l)+half(w2.x*w1.y)*tap(float2(o2.x,o1.y),l)+half(w1.x*w2.y)*tap(float2(o1.x,o2.y),l)+half(w2.x*w2.y)*tap(float2(o2.x,o2.y),l);}\n"+
        "half4 main(float2 p){float t=clamp((p.y-"+FULL_FRACTION+"*H)/("+(1f - FULL_FRACTION)+"*H),0.,1.);float r=radius*(1.-smoothstep(0.,1.,t));\n"+
        " float m=clamp(log2(max(r,.00001)*1.33333333),0.,6.);int lo=int(floor(m));return mix(level(p,lo),level(p,(lo<6?lo+1:6)),half(fract(m)));}\n";

    private final RenderNode[] levels = new RenderNode[LEVELS];
    private final RenderNode atlas = new RenderNode("Gaussian pyramid atlas");
    private final RenderNode output = new RenderNode("Progressive band output");
    private final RuntimeShader shader = new RuntimeShader(CODE);
    private final int[] left = new int[LEVELS];
    private float radius = -1f;

    EdgeBlurPyramid(int w, int h, float band) {
        float[] offsets = new float[LEVELS], widths = new float[LEVELS], heights = new float[LEVELS];
        int offset = 0;
        for (int i = 0; i < LEVELS; i++) {
            int lw = Math.max(1, (int) Math.ceil(w / (float) (1 << i)));
            int lh = Math.max(1, (int) Math.ceil(h / (float) (1 << i)));
            left[i] = offset;
            offsets[i] = offset;
            widths[i] = lw;
            heights[i] = lh;
            offset += lw + 4;
            levels[i] = new RenderNode("Gaussian level " + i);
            levels[i].setPosition(0, 0, lw, lh);
            // Android converts a blur radius to sigma ~= .57735r + .5, so a 0.25px radius per
            // octave gives sigma ~= .645 there. Accumulated variance approaches sigma ~= .745 * 2^L,
            // which matches r = .75 * 2^L in the level selection above.
            if (i > 0) levels[i].setRenderEffect(RenderEffect.createBlurEffect(.25f, .25f, Shader.TileMode.CLAMP));
        }
        atlas.setPosition(0, 0, offset, h);
        // Keep the atlas input's full bounds; the caller clips only the final output.
        output.setPosition(0, 0, offset, h);
        shader.setFloatUniform("H", band);
        shader.setFloatUniform("offsets", offsets);
        shader.setFloatUniform("widths", widths);
        shader.setFloatUniform("heights", heights);
    }

    void draw(Canvas canvas, RenderNode source, float radius) {
        // The effect copies the uniforms when it is created, so a new radius needs a new effect.
        if (radius != this.radius) {
            this.radius = radius;
            shader.setFloatUniform("radius", radius);
            output.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "atlas"));
        }
        for (int i = 0; i < LEVELS; i++) {
            Canvas c = levels[i].beginRecording();
            if (i == 0) {
                c.drawRenderNode(source);
            } else {
                c.scale(.5f, .5f);
                c.drawRenderNode(levels[i - 1]);
            }
            levels[i].endRecording();
        }
        Canvas a = atlas.beginRecording();
        for (int i = 0; i < LEVELS; i++) {
            a.save();
            a.translate(left[i], 0);
            a.drawRenderNode(levels[i]);
            a.restore();
        }
        atlas.endRecording();
        Canvas c = output.beginRecording();
        c.drawRenderNode(atlas);
        output.endRecording();
        canvas.drawRenderNode(output);
    }

    void release() {
        for (RenderNode level : levels) level.discardDisplayList();
        atlas.discardDisplayList();
        output.discardDisplayList();
    }
}
