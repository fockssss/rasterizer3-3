package Main;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import Datatypes.Triangle;
import Datatypes.Vec;
import ModelHandler.Light;
import ModelHandler.Material;
import ModelHandler.Obj;
import io.github.mudbill.dds.DDSFile;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBBindlessTexture.*;
import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL45.*;
import static org.lwjgl.stb.STBImage.stbi_image_free;
import static org.lwjgl.stb.STBImage.stbi_load;
import static org.lwjgl.system.MemoryUtil.*;

public class World {

    public List<worldObject> worldObjects = new ArrayList<>();
    public List<worldLight> worldLights = new ArrayList<>();
    private List<Material> worldMaterials = new ArrayList<>();
    private List<String> texturePaths = new ArrayList<>();

    public int materialSSBO, textureHandleSSBO, lightDataSSBO, shadowmapHandleSSBO;

    public int triCount = 0;

    public World() {}

    public void updateWorld() {
        float numMatParams = getMaterialFieldCount();
        FloatBuffer materialData = memAllocFloat((int) numMatParams * worldMaterials.size() + 1);
        materialData.put(numMatParams);
        for (Material material : worldMaterials) {
            System.out.print("\rparsing material: " + material.name);
            for (Field field : material.getClass().getDeclaredFields()) {
                if (!(field.getName().equals("name") || field.getName().equals("texturesDirectory"))) {
                    field.setAccessible(true);
                    try {
                        Object value = field.get(material);
                        if (value instanceof Number) {
                            materialData.put(((Number) value).floatValue());
                        } else if (value instanceof Vec) {
                            Vec vecValue = (Vec) value;
                            vecValue.updateFloats();
                            materialData.put(vecValue.xf);
                            materialData.put(vecValue.yf);
                            materialData.put(vecValue.zf);
                        } else if (value instanceof String) {
                            materialData.put((float) texturePaths.indexOf(value));
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        materialData.flip();
        materialSSBO = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, materialData, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, materialSSBO);
        memFree(materialData);

        LongBuffer textureHandles = memAllocLong(texturePaths.size());
        int counter = 0;
        for (String path : texturePaths) {
            counter++;
            System.out.print("\ruploading texture (" + counter + "/" + texturePaths.size() + ") from " + path + "...");
            int textureID = 0;

            if (path.endsWith(".dds")) {
                DDSFile ddsFile;
                try {
                    ddsFile = new DDSFile(path);
                    textureID = glGenTextures();
                    glActiveTexture(GL13.GL_TEXTURE0);     // Depends on your implementation
                    glBindTexture(GL11.GL_TEXTURE_2D, textureID);
                    for (int level = 0; level < ddsFile.getMipMapCount(); level++)
                        GL13.glCompressedTexImage2D(
                                GL11.GL_TEXTURE_2D,
                                level,
                                ddsFile.getFormat(),
                                ddsFile.getWidth(level),
                                ddsFile.getHeight(level),
                                0,
                                ddsFile.getBuffer(level)
                        );
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, ddsFile.getMipMapCount() - 1);
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                textureID = glCreateTextures(GL_TEXTURE_2D);
                IntBuffer width = MemoryUtil.memAllocInt(1);
                IntBuffer height = MemoryUtil.memAllocInt(1);
                IntBuffer channels = MemoryUtil.memAllocInt(1);
                ByteBuffer image = stbi_load(path, width, height, channels, 4);
                if (image == null) {
                    System.err.println("could not load image " + path);
                    continue;
                }
                glTextureStorage2D(textureID, 1, GL_RGBA8, width.get(0), height.get(0));
                glTextureSubImage2D(textureID, 0, 0, 0, width.get(0), height.get(0), GL_RGBA, GL_UNSIGNED_BYTE, image);
                glTextureParameteri(textureID, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTextureParameteri(textureID, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glGenerateMipmap(GL_TEXTURE_2D);

                stbi_image_free(image);
                MemoryUtil.memFree(width);
                MemoryUtil.memFree(height);
                MemoryUtil.memFree(channels);
            }

            long handle = glGetTextureHandleARB(textureID);
            glMakeTextureHandleResidentARB(handle);
            textureHandles.put(handle);
            if (!glIsTextureHandleResidentARB(handle)) {
                System.err.println("texture handle is not resident: " + handle);
            }
        }
        textureHandles.flip();
        textureHandleSSBO = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, textureHandleSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, textureHandles, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, textureHandleSSBO);
        memFree(textureHandles);

        FloatBuffer lightData = MemoryUtil.memAllocFloat(worldLights.size() * 24);
        for (worldLight wlight : worldLights) {
            Light light = wlight.light;
            for (Field field : light.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object value = field.get(light);
                    if (value instanceof Number) {
                        lightData.put(((Number) value).floatValue());
                    } else if (value instanceof Vec) {
                        Vec vecValue = (Vec) value;
                        vecValue.updateFloats();
                        lightData.put(vecValue.xf);
                        lightData.put(vecValue.yf);
                        lightData.put(vecValue.zf);
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        lightData.flip();
        lightDataSSBO = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, lightDataSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, lightData, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, lightDataSSBO);
        memFree(lightData);

        LongBuffer shadowmapHandles = MemoryUtil.memAllocLong(worldLights.size());
        for (worldLight wlight : worldLights) {
            shadowmapHandles.put(wlight.shadowmapTexHandle);
        }
        shadowmapHandles.flip();
        shadowmapHandleSSBO = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, shadowmapHandleSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, shadowmapHandles, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, shadowmapHandleSSBO);
        memFree(shadowmapHandles);

        FloatBuffer lightSpaceMatrixBuffer = MemoryUtil.memAllocFloat(worldLights.size() * 16);
        for (worldLight wLight : worldLights) {
            Light light = wLight.light;
            light.lightSpaceMatrix.get(lightSpaceMatrixBuffer);
        }
        //lightSpaceMatrixBuffer.flip();
        int lightSpaceMatrixSSBO = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, lightSpaceMatrixSSBO);
        glBufferData(GL_SHADER_STORAGE_BUFFER, lightSpaceMatrixBuffer, GL_STATIC_DRAW);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, lightSpaceMatrixSSBO);
        memFree(lightSpaceMatrixBuffer);
    }

    public void addObject(String filePath, Vec scale, Vec translation, Vec rotation, String identifier) {
        worldObject newWorldObject = new worldObject();
        newWorldObject.identifer = identifier;
        Obj newObj = new Obj(filePath, scale, translation, rotation);
        System.out.println("Parsed object: " + newWorldObject.identifer);
        newWorldObject.object = newObj;
        int lastNumMats = worldMaterials.size();
        worldMaterials.addAll(newObj.mtllib);
        for (String texPath : newObj.texturePaths) {
            if(!texturePaths.contains(texPath)) {
                texturePaths.add(texPath);
            }
        }
        FloatBuffer verticesBuffer = MemoryUtil.memAllocFloat(45*newObj.triangles.size());

        int totalTriangles = newObj.triangles.size();
        int progressInterval = Math.max(totalTriangles / 10, 1); // Update every 10% or at least every 1 triangle
        for (int i = 0; i < totalTriangles; i++) {
            Triangle triangle = newObj.triangles.get(i);
            newWorldObject.triCount++;
            verticesBuffer.put(triangle.v1.toFloatArray());
            verticesBuffer.put(triangle.vt1.toUVfloatArray());
            verticesBuffer.put(triangle.n1.toFloatArray());
            verticesBuffer.put((float) triangle.material + lastNumMats);
            verticesBuffer.put(triangle.t1.toFloatArray());
            verticesBuffer.put(triangle.bt1.toFloatArray());
            verticesBuffer.put(triangle.v2.toFloatArray());
            verticesBuffer.put(triangle.vt2.toUVfloatArray());
            verticesBuffer.put(triangle.n2.toFloatArray());
            verticesBuffer.put((float) triangle.material + lastNumMats);
            verticesBuffer.put(triangle.t2.toFloatArray());
            verticesBuffer.put(triangle.bt2.toFloatArray());
            verticesBuffer.put(triangle.v3.toFloatArray());
            verticesBuffer.put(triangle.vt3.toUVfloatArray());
            verticesBuffer.put(triangle.n3.toFloatArray());
            verticesBuffer.put((float) triangle.material + lastNumMats);
            verticesBuffer.put(triangle.t3.toFloatArray());
            verticesBuffer.put(triangle.bt3.toFloatArray());
            if (i % progressInterval == 0 || i == totalTriangles - 1) {
                System.out.print("\rProcessed " + (i + 1) + " / " + totalTriangles + " triangles (" +
                        (int) ((i + 1) / (float) totalTriangles * 100) + "%)");
            }
        }
        verticesBuffer.flip();
        newWorldObject.VAO = glGenVertexArrays();
        glBindVertexArray(newWorldObject.VAO);
        newWorldObject.VBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, newWorldObject.VBO);
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 15*Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        //vertex texCoord
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 15*Float.BYTES, 3*Float.BYTES);
        glEnableVertexAttribArray(1);
        //vertex normal
        glVertexAttribPointer(2, 3, GL_FLOAT, false, 15*Float.BYTES, 5*Float.BYTES);
        glEnableVertexAttribArray(2);
        //vertex material
        glVertexAttribPointer(3, 1, GL_FLOAT, false, 15*Float.BYTES, 8*Float.BYTES);
        glEnableVertexAttribArray(3);
        //vertex tangent
        glVertexAttribPointer(4, 3, GL_FLOAT, false, 15*Float.BYTES, 9*Float.BYTES);
        glEnableVertexAttribArray(4);
        //vertex bitangent
        glVertexAttribPointer(5, 3, GL_FLOAT, false, 15*Float.BYTES, 12*Float.BYTES);
        glEnableVertexAttribArray(5);

        memFree(verticesBuffer);
        worldObjects.add(newWorldObject);
    }
    public void addLight(Light light) {
        worldLight newLight = new worldLight();
        newLight.light = light;
        newLight.transform = new Matrix4f().identity();

        newLight.shadowmapFramebuffer = glGenFramebuffers();
        newLight.shadowmapTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, newLight.shadowmapTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, Run.SHADOW_RES, Run.SHADOW_RES, 0, GL_DEPTH_COMPONENT, GL_FLOAT, MemoryUtil.NULL);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        float[] borderColor = { 1.0f, 1.0f, 1.0f, 1.0f };
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, borderColor);

        glBindFramebuffer(GL_FRAMEBUFFER, newLight.shadowmapFramebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, newLight.shadowmapTexture, 0);
        glDrawBuffer(GL_NONE);
        glReadBuffer(GL_NONE);
        newLight.shadowmapTexHandle = glGetTextureHandleARB(newLight.shadowmapTexture);
        glMakeTextureHandleResidentARB(newLight.shadowmapTexture);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        worldLights.add(newLight);
    }

    public void modifyLight() {

    }

    public static class worldObject {
        String identifer;
        Obj object;
        int triCount = 0;
        int VBO;
        int VAO;
        List<Matrix4f> transforms = new ArrayList<>();
        int numInstances = 0;
        List<Boolean> outlined = new ArrayList<>();

        public void newInstance(Vec scale, Vec translation, Vec rotation) {
            rotation.updateFloats();
            transforms.add(new Matrix4f().identity()
                    .translate(translation.toVec3f())
                    .rotateX(rotation.xF)
                    .rotateY(rotation.yF)
                    .rotateZ(rotation.zF)
                    .scale(scale.toVec3f()));
            outlined.add(false);
            numInstances++;
        }
        public void newInstance() {
            transforms.add(new Matrix4f().identity());
            outlined.add(false);
            numInstances++;
        }

        public void removeInstance(int instanceID) {
            transforms.remove(instanceID);
            outlined.remove(instanceID);
            numInstances--;
        }

        public void stepInstance(int instanceID, Vec scale, Vec translation, Vec rotation) {
            rotation.updateFloats();
            transforms.get(instanceID).mul(new Matrix4f().identity()
                    .translate(translation.toVec3f())
                    .rotateX(rotation.xF)
                    .rotateY(rotation.yF)
                    .rotateZ(rotation.zF)
                    .scale(scale.toVec3f()));
        }
        public void setInstance(int instanceID, Vec scale, Vec translation, Vec rotation) {
            rotation.updateFloats();
            transforms.set(instanceID, new Matrix4f().identity()
                    .translate(translation.toVec3f())
                    .rotateX(rotation.xF)
                    .rotateY(rotation.yF)
                    .rotateZ(rotation.zF)
                    .scale(scale.toVec3f()));
        }
        public void toggleInstanceOutline(int instanceID) {
            outlined.set(instanceID, !outlined.get(instanceID));
        }
    }
    public static class worldLight {
        String identifer;
        int shadowmapFramebuffer;
        int shadowmapTexture;
        long shadowmapTexHandle;
        Matrix4f transform;
        Light light;
    }

    private float getMaterialFieldCount() {
        float materialPropertyCount = 0;
        for (Field field : Material.class.getDeclaredFields()) {
            if (!(field.getName().equals("name") || field.getName().equals("texturesDirectory"))) {
                field.setAccessible(true);
                try {
                    Class<?> type = field.getType();
                    if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
                        materialPropertyCount++;
                    } else if (type == Vec.class) {
                        materialPropertyCount+=3;
                    } else if (type == String.class) {
                        materialPropertyCount++;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return materialPropertyCount;
    }
}
