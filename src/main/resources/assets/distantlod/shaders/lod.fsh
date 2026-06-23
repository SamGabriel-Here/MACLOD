#version 150

// Reference fragment shader for the FUTURE custom-shader path (Week 7-8).
// Demonstrates the seam-hiding trick: fade LOD terrain in over distance so the
// boundary between vanilla chunks and LOD geometry is soft, not a hard line.

in vec4 vColor;
in float vViewDist;

uniform float uNearFadeStart;  // distance where LOD begins to appear
uniform float uNearFadeEnd;    // distance where LOD is fully opaque

out vec4 fragColor;

void main() {
    float lodFade = smoothstep(uNearFadeStart, uNearFadeEnd, vViewDist);
    fragColor = vec4(vColor.rgb, vColor.a * lodFade);
    if (fragColor.a < 0.01) discard;
}
