#version 460
#extension GL_ARB_bindless_texture : require
#extension GL_ARB_gpu_shader_int64 : require
out vec4 fragColor;
in vec2 texCoord;

uniform sampler2D gPosition;
uniform sampler2D gNormal;
uniform sampler2D gMaterial;
uniform sampler2D gTexCoord;
uniform sampler2D gViewFragPos;
uniform sampler2D SSAOtex;

uniform vec3 camPos;
uniform vec3 camRot;

layout(std430, binding = 0) buffer mtlBuffer {
    float mtlData[];
};
layout(std430, binding = 1) buffer handleBuffer {
    sampler2D textures[];
};
layout(std430, binding = 2) buffer lightBuffer {
    float lightData[];
};
layout(std430, binding = 4) buffer lightSpaceMatrixBuffer {
    mat4 lightSpaceMatrices[];
};
uniform sampler2D shadowmaps;

int lightFields = 24;
int mtlFields = int(mtlData[0]);
mat3 rotateX(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(
    1.0, 0.0, 0.0,
    0.0,   c,  -s,
    0.0,   s,   c
    );
}
mat3 rotateY(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(
    c, 0.0,   s,
    0.0, 1.0, 0.0,
    -s, 0.0,   c
    );
}
mat3 rotateZ(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat3(
    c,  -s, 0.0,
    s,   c, 0.0,
    0.0, 0.0, 1.0
    );
}
mat3 rotationMatrix(vec3 angles) {
    return rotateX(angles.x) * rotateY(angles.y) * (angles.z != 0 ? rotateZ(angles.z) : mat3(1));
}
vec3 rotate(vec3 p, vec3 rot) {
    mat3 rm = rotationMatrix(rot);
    return p * rm;
}
struct light {
    int type; // 0 = point, 1 = directional, 2 = spotlight

    vec3 ambient;
    vec3 diffuse;
    vec3 specular;

    vec3 position; // excluded by directionalLight
    vec3 direction; // excluded by pointLight
// pointLight exclusive
    float constantAtten;
    float linearAtten;
    float quadraticAtten;
// spotlight exclusive
    float cutOff;
    float innerCutoff;
    float outerCutoff;

    sampler2D shadowMap;
    mat4 lightSpaceMatrix;
};
struct mtl {
    vec3 Ka; vec3 Kd; vec3 Ks;
    float Ns;// specular exponent
    float d;// dissolved (transparency 1-0, 1 is opaque)
    float Tr;// occasionally used, opposite of d (0 is opaque)
    vec3 Tf;// transmission filter
    float Ni;// refractive index
    vec3 Ke;// emission color
    int illum;// shading model (0-10, each has diff properties)
    int map_Ka; int map_Kd; int map_Ks;
//PBR extension types
    float Pm;// metallicity (0-1, dielectric to metallic)
    float Pr;// roughness (0-1, perfectly smooth to "extremely" rough)
    float Ps;// sheen (0-1, no sheen effect to maximum sheen)
    float Pc;// clearcoat thickness (0-1, smooth clearcoat to rough clearcoat (blurry reflections))
    float Pcr;
    float aniso;// anisotropy (0-1, isotropic surface to fully anisotropic) (uniform-directional reflections)
    float anisor;// rotational anisotropy (0-1, but essentially 0-2pi, rotates pattern of anisotropy)
    int map_Pm; int map_Pr; int map_Ps; int map_Pc; int map_Pcr; int map_norm; int map_d; int map_Tr; int map_Ns; int map_Ke; int map_Disp;
//CUSTOM
    float Density; float subsurface;
    vec3 subsurfaceColor; vec3 subsurfaceRadius;

    vec3 normal;
};
vec4 sampleTexture(int textureIndex, vec2 uv) {
    return texture(textures[textureIndex], uv).rgba;
}
mtl newMtl(int m) {
    mtl Out;
    Out.Ka = vec3(mtlData[mtlFields*m + 1], mtlData[mtlFields*m + 2], mtlData[mtlFields*m + 3]);
    Out.Kd = vec3(mtlData[mtlFields*m + 4], mtlData[mtlFields*m + 5], mtlData[mtlFields*m + 6]);
    Out.Ks = vec3(mtlData[mtlFields*m + 7], mtlData[mtlFields*m + 8], mtlData[mtlFields*m + 9]);
    Out.Ns = mtlData[mtlFields*m + 10];
    Out.d = mtlData[mtlFields*m + 11];
    Out.Tr = mtlData[mtlFields*m + 12];
    Out.Tf = vec3(mtlData[mtlFields*m + 13], mtlData[mtlFields*m + 14], mtlData[mtlFields*m + 15]);
    Out.Ni = mtlData[mtlFields*m + 16];
    Out.Ke = vec3(mtlData[mtlFields*m + 17], mtlData[mtlFields*m + 18], mtlData[mtlFields*m + 19]);
    Out.illum = int(mtlData[mtlFields*m + 20]);
    Out.map_Ka = int(mtlData[mtlFields*m + 21]);
    Out.map_Kd = int(mtlData[mtlFields*m + 22]);
    Out.map_Ks = int(mtlData[mtlFields*m + 23]);
    //pbr extension
    Out.Pm = mtlData[mtlFields*m + 24];
    Out.Pr = mtlData[mtlFields*m + 25];
    Out.Ps = mtlData[mtlFields*m + 26];
    Out.Pc = mtlData[mtlFields*m + 27];
    Out.Pcr = mtlData[mtlFields*m + 28];
    Out.aniso = mtlData[mtlFields*m + 29];
    Out.anisor = mtlData[mtlFields*m + 30];
    Out.map_Pm = int(mtlData[mtlFields*m + 31]);
    Out.map_Pr = int(mtlData[mtlFields*m + 32]);
    Out.map_Ps = int(mtlData[mtlFields*m + 33]);
    Out.map_Pc = int(mtlData[mtlFields*m + 34]);
    Out.map_Pcr = int(mtlData[mtlFields*m + 35]);
    Out.map_norm = int(mtlData[mtlFields*m + 36]);
    Out.map_d = int(mtlData[mtlFields*m + 37]);
    Out.map_Tr = int(mtlData[mtlFields*m + 38]);
    Out.map_Ns = int(mtlData[mtlFields*m + 39]);
    Out.map_Ke = int(mtlData[mtlFields*m + 40]);
    Out.map_Disp = int(mtlData[mtlFields*m + 41]);
    //CUSTOM
    Out.Density = mtlData[mtlFields*m + 42];
    Out.subsurface = mtlData[mtlFields*m + 43];
    Out.subsurfaceColor = vec3(mtlData[mtlFields*m + 44], mtlData[mtlFields*m + 45], mtlData[mtlFields*m + 46]);
    Out.subsurfaceRadius = vec3(mtlData[mtlFields*m + 47], mtlData[mtlFields*m + 48], mtlData[mtlFields*m + 49]);

    Out.normal = vec3(0);
    return Out;
}
mtl mapMtl(mtl M, vec2 uv) {
    // Mapped materials are reset to mapped values, otherwise are unchanged
    mtl m = M;
    m.Ka = ((M.map_Ka > -1) ? sampleTexture(M.map_Ka, uv).xyz * M.Ka: M.Ka);
    if (M.map_Kd > -1) {
        vec4 sampled = sampleTexture(M.map_Kd, uv);
        m.Kd = sampled.rgb;
        m.d = sampled.a;
        m.Tr = sampled.a;
    } else {
        vec4 sampled = sampleTexture(M.map_d, uv);
        m.Kd = M.Kd;
        m.d = ((M.map_d > -1) ? sampled.r : M.d);
        m.Tr = ((M.map_Tr > -1) ? sampled.r : M.Tr);
    }
    m.Ks = ((M.map_Ks > -1) ? sampleTexture(M.map_Ks, uv).rgb : M.Ks);
    m.Ke = ((M.map_Ke > -1) ? sampleTexture(M.map_Ke, uv).rgb : M.Ke);
    m.Ns = ((M.map_Ns > -1) ? sampleTexture(M.map_Ns, uv).r : M.Ns);
    m.Pm = ((M.map_Pm > -1) ? sampleTexture(M.map_Pm, uv).r : M.Pm);
    m.Pr = ((M.map_Pr > -1) ? sampleTexture(M.map_Pr, uv).r : M.Pr);
    m.Ps = ((M.map_Ps > -1) ? sampleTexture(M.map_Ps, uv).r : M.Ps);
    m.Pc = ((M.map_Pc > -1) ? sampleTexture(M.map_Pc, uv).r : M.Pc);
    return m;
}
light newLight(int n) {
    light Out;
    Out.type = int(lightData[lightFields*n + 0]);

    Out.ambient = vec3(lightData[lightFields*n + 1], lightData[lightFields*n + 2], lightData[lightFields*n + 3]);
    Out.diffuse = vec3(lightData[lightFields*n + 4], lightData[lightFields*n + 5], lightData[lightFields*n + 6]);
    Out.specular = vec3(lightData[lightFields*n + 7], lightData[lightFields*n + 8], lightData[lightFields*n + 9]);

    Out.position = vec3(lightData[lightFields*n + 10], lightData[lightFields*n + 11], lightData[lightFields*n + 12]);
    Out.direction = vec3(lightData[lightFields*n + 13], lightData[lightFields*n + 14], lightData[lightFields*n + 15]);

    Out.constantAtten = lightData[lightFields*n + 16];
    Out.linearAtten = lightData[lightFields*n + 17];
    Out.quadraticAtten = lightData[lightFields*n + 18];

    Out.cutOff = lightData[lightFields*n + 19];
    Out.innerCutoff = lightData[lightFields*n + 20];
    Out.outerCutoff = lightData[lightFields*n + 21];

    Out.shadowMap = shadowmaps;
    Out.lightSpaceMatrix = lightSpaceMatrices[n];
    return Out;
}
float attenuation(vec3 lPos, vec3 pos, float constant, float linear, float quadratic) {
    float distance = length(lPos - pos);
    return 1 / (constant + linear*distance + quadratic*(distance*distance));
}
vec4 getLighting(light l, vec3 pos) {
    vec4 Out;
    if (l.type == 0) {
        Out.w = attenuation(l.position, pos, l.constantAtten, l.linearAtten, l.quadraticAtten);
        Out.xyz = normalize(l.position - pos);
    } else if (l.type == 1) {
        Out.w = 1;
        Out.xyz = -l.direction;
    } else if (l.type == 2) {
        float theta = dot(normalize(pos - l.position), normalize(l.direction));
        float epsilon = l.cutOff - l.innerCutoff;
        float intensity = clamp((l.innerCutoff - theta) / epsilon, 0, 1);
        if (theta > l.cutOff) {
            Out.w = intensity;
            Out.xyz = normalize(l.position - pos);
        }
    } else if (l.type == 3) {
        float theta = dot(normalize(pos - camPos*vec3(-1,1,-1)), normalize(rotate(vec3(0,0,-1), camRot*vec3(-1,1,1))));
        float epsilon = l.cutOff - l.innerCutoff;
        float intensity = clamp((l.innerCutoff - theta) / epsilon, 0, 1);
        if (theta > l.cutOff) {
            Out.w = intensity;
            Out.xyz = normalize(camPos - pos);
        }
    }
    return Out;
}
float calculateShadow(light l, vec3 fragPos, vec3 normal) {
    vec4 fragPosLightSpace = l.lightSpaceMatrix * vec4(fragPos, 1);
    vec3 projCoords = (fragPosLightSpace.xyz / fragPosLightSpace.w) * 0.5 + 0.5;
    float closestDepth = texture(l.shadowMap, projCoords.xy).r;
    float currentDepth = projCoords.z;
    vec3 lightDir = normalize(fragPos - l.position);
    float bias = max(0.0001 * (1.0 - dot(normal, -lightDir)), 0.0001);

    float shadow = 0.0;
    vec2 texelSize = 1.0 / textureSize(l.shadowMap, 0);
    for(int x = -1; x <= 1; ++x)
    {
        for(int y = -1; y <= 1; ++y)
        {
            float pcfDepth = texture(l.shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
            shadow += currentDepth - bias > pcfDepth  ? 1.0 : 0.0;
        }
    }
    shadow /= 9.0;

    if(projCoords.z > 1.0)
    shadow = 0.0;

    return 1-shadow;
}

void main() {
    vec3 thisNormal = (texture(gNormal, texCoord).rgb - 0.5) * 2;
    if (thisNormal.length() == 0) {discard;} else {
        int thisMtlID = int(texture(gMaterial, texCoord).r*1000);
        vec3 thisPosition = texture(gPosition, texCoord).rgb;
        vec2 thisTexCoord = texture(gTexCoord, texCoord).rg;
        mtl thisMtl = newMtl(thisMtlID);
        thisMtl = mapMtl(thisMtl, thisTexCoord);

        vec3 fragPos = thisPosition;
        vec3 albedo = thisMtl.Kd;
        vec3 normal = thisNormal;

        vec3 lighting = (albedo * 0.1 * texture(SSAOtex, texCoord).r) + thisMtl.Ke; //ambient preset
        vec3 V = normalize(camPos - fragPos);
        for (int i = 0; i < int(lightData.length()/lightFields)+1; i++) {
            light l = newLight(i);
            vec4 thisLighting = getLighting(l,fragPos);
            vec3 L = thisLighting.xyz;
            float atten = thisLighting.w;
            vec3 diffuse = max(albedo*dot(L, normal)*l.diffuse, 0) * atten;
            vec3 halfwayDir = normalize(L + V);
            vec3 specular = l.specular * pow(max(dot(normal, halfwayDir),0),thisMtl.Ns) * atten;
            //lighting+=(diffuse+specular);
            lighting+=(diffuse+specular) * calculateShadow(l, fragPos, normal);
        }
        fragColor = vec4(lighting, 1);

        //fragColor = vec4(thisNormal*0.5 + 0.5,1);
        //fragColor = vec4(thisAlbedo,1);
    }
}
