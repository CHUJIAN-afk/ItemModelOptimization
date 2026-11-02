package first.itemmodeloptimization.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

final class GpuItemBatch implements AutoCloseable {
    final ItemStack stack;
    final BakedModel model;
    final List<Mesh> meshes = new ArrayList<>();
    final float groundScaleY;
    final float copySpacing;
    final float[] copyOffsets = new float[15];
    final IClientItemExtensions extensions;
    final int instanceStride;
    final boolean entityBatch;
    final int instanceBuffer;
    long meshBytes;
    int count;
    int capacity;
    int lastFrame;
    boolean flushed;
    boolean closed;
    private ByteBuffer data;
    private ByteBuffer previous;
    private int gpuCapacity;
    private int uploadedCount;
    int preparedBuffer;
    int preparedCapacity;
    boolean processed;
    int retainedCount;

    GpuItemBatch(ItemStack stack, BakedModel model, ItemBatchRenderer renderer, boolean entityBatch) {
        this.stack = stack.copy();
        this.model = model;
        this.entityBatch = entityBatch;
        instanceStride = entityBatch ? 40 : 144;
        groundScaleY = model.getTransforms().ground.scale.y();
        boolean gui3d = model.isGui3d();
        copySpacing = gui3d ? 0 : 0.09375f * model.getTransforms().ground.scale.z();
        extensions = IClientItemExtensions.of(stack);
        RandomSource copyRandom = RandomSource.create(ItemEntityRenderer.getSeedForItemStack(stack));
        float spread = gui3d ? 0.15f : 0.075f;
        for (int copy = 1; copy < 5; copy++) {
            int offset = copy * 3;
            copyOffsets[offset] = (copyRandom.nextFloat() * 2 - 1) * spread;
            copyOffsets[offset + 1] = (copyRandom.nextFloat() * 2 - 1) * spread;
            if (gui3d) {
                copyOffsets[offset + 2] = (copyRandom.nextFloat() * 2 - 1) * spread;
            }
        }
        instanceBuffer = glGenBuffers();
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        Map<RenderType, ByteBufferBuilder> allocators = new LinkedHashMap<>();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        renderer.capturing = true;
        try {
            for (BakedModel pass : model.getRenderPasses(stack, true)) {
                boolean translucent = pass.getRenderTypes(stack, true).stream().anyMatch(type ->
                        type == RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
                                || type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS));
                RandomSource random = RandomSource.create(42);
                for (int face = -1; face < 6; face++) {
                    random.setSeed(42);
                    for (BakedQuad quad : pass.getQuads(null, face < 0 ? null : Direction.from3DDataValue(face), random)) {
                        if (translucent) {
                            boolean opaque = renderer.opaqueSprites.computeIfAbsent(quad.getSprite().contents(), sprite -> {
                                NativeImage image = sprite.getOriginalImage();
                                boolean binaryAlpha = true;
                                if (image.format().hasAlpha()) {
                                    for (int y = 0; y < image.getHeight() && binaryAlpha; y++) {
                                        for (int x = 0; x < image.getWidth() && binaryAlpha; x++) {
                                            int alpha = image.getPixelRGBA(x, y) >>> 24;
                                            binaryAlpha = alpha == 0 || alpha == 255;
                                        }
                                    }
                                }
                                return binaryAlpha;
                            });
                            if (!opaque) {
                                throw new IllegalArgumentException("Translucent pixels require vanilla sorting");
                            }
                        }
                        int[] vertices = quad.getVertices();
                        for (int i = 3; i < vertices.length; i += 8) {
                            int alpha = vertices[i] >>> 24;
                            if (alpha != 0 && alpha != 255) {
                                throw new IllegalArgumentException("Translucent vertex color requires vanilla sorting");
                            }
                        }
                    }
                }
            }
            Minecraft.getInstance().getItemRenderer().render(stack, ItemDisplayContext.GROUND, false, new PoseStack(),
                    type -> {
                        RenderType target = type == RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
                                || type == RenderType.itemEntityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
                                ? RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS) : type;
                        return builders.computeIfAbsent(target, key -> {
                            if (key.format() != DefaultVertexFormat.NEW_ENTITY || key.mode() != VertexFormat.Mode.QUADS) {
                                throw new IllegalArgumentException("Unsupported vertex format");
                            }
                            ByteBufferBuilder allocator = new ByteBufferBuilder(4096);
                            allocators.put(key, allocator);
                            return new BufferBuilder(allocator, key.mode(), key.format());
                        });
                    }, 0, OverlayTexture.NO_OVERLAY, model);
            for (Map.Entry<RenderType, BufferBuilder> entry : builders.entrySet()) {
                try (MeshData mesh = entry.getValue().build()) {
                    if (mesh != null) {
                        int vertices = mesh.drawState().vertexCount();
                        ByteBuffer vertexData = mesh.vertexBuffer();
                        for (int i = 0; i < vertices; i++) {

                            int alpha = vertexData.get(i * 36 + 15) & 255;
                            if (alpha != 0 && alpha != 255) {
                                throw new IllegalArgumentException("Translucent vertex color requires vanilla sorting");
                            }
                        }
                        Mesh part = new Mesh(entry.getKey(), vertexData, vertices);
                        meshes.add(part);
                        meshBytes += vertexData.remaining() + part.indices * 4L;
                    }
                }
            }
            if (meshes.isEmpty()) {
                throw new IllegalArgumentException("Empty model");
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        } finally {
            renderer.capturing = false;
            for (ByteBufferBuilder allocator : allocators.values()) {
                allocator.close();
            }
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
        }
    }

    void add(Matrix4fc matrix, Matrix3fc normal, float ageOffset, float phase, boolean animated,
             int light, boolean bob, float x, float y, float z) {
        ensureCapacity(1);
        int offset = count++ * instanceStride;
        matrix.get(offset, data);
        data.putFloat(offset + 64, ageOffset);
        data.putFloat(offset + 68, phase);
        data.putFloat(offset + 72, animated ? 1 : 0);
        data.putFloat(offset + 76, bob ? 1 : 0);
        data.putFloat(offset + 80, light & 65535);
        data.putFloat(offset + 84, light >>> 16);
        normal.get(offset + 88, data);
        data.putFloat(offset + 124, x);
        data.putFloat(offset + 128, y);
        data.putFloat(offset + 132, z);
        data.putLong(offset + 136, 0);
    }

    void addEntity(float x, float y, float z, float age, float phase, int light, boolean bob, boolean spread, int copies) {
        ensureCapacity(copies);
        long address = MemoryUtil.memAddress(data) + (long) count * instanceStride;
        float center = (copies - 1) * 0.5f;
        for (int copy = 0; copy < copies; copy++) {
            int offset = copy * 3;
            MemoryUtil.memPutFloat(address, x);
            MemoryUtil.memPutFloat(address + 4, y);
            MemoryUtil.memPutFloat(address + 8, z);
            MemoryUtil.memPutInt(address + 12, light);
            MemoryUtil.memPutFloat(address + 16, age);
            MemoryUtil.memPutFloat(address + 20, phase);
            MemoryUtil.memPutFloat(address + 24, spread ? copyOffsets[offset] : 0);
            MemoryUtil.memPutFloat(address + 28, spread ? copyOffsets[offset + 1] : 0);
            MemoryUtil.memPutFloat(address + 32, (copy - center) * copySpacing + (spread ? copyOffsets[offset + 2] : 0));
            MemoryUtil.memPutFloat(address + 36, bob ? 1 : 0);
            address += instanceStride;
        }
        count += copies;
    }

    private void ensureCapacity(int additional) {
        if (count + additional > capacity) {
            capacity = Math.max(64, Math.max(capacity * 2, count + additional));
            data = data == null ? MemoryUtil.memAlloc(capacity * instanceStride) : MemoryUtil.memRealloc(data, capacity * instanceStride);
            previous = previous == null ? MemoryUtil.memAlloc(capacity * instanceStride) : MemoryUtil.memRealloc(previous, capacity * instanceStride);
        }
    }

    long upload() {
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        int bytes = count * instanceStride;
        int first = 0;
        int last = bytes;
        if (gpuCapacity < capacity) {
            glBufferData(GL_ARRAY_BUFFER, capacity * (long) instanceStride, GL_DYNAMIC_DRAW);
            gpuCapacity = capacity;
        } else {
            int common = Math.min(count, uploadedCount) * instanceStride;
            first = data.slice(0, common).mismatch(previous.slice(0, common));
            if (first < 0) {
                first = common;
            }
            if (count <= uploadedCount && first < bytes) {
                while (last > first && data.get(last - 1) == previous.get(last - 1)) {
                    last--;
                }
            }
        }
        if (first < last) {
            first &= ~3;
            last = Math.min(bytes, (last + 3) & ~3);
            glBufferSubData(GL_ARRAY_BUFFER, first, data.slice(first, last - first));
            MemoryUtil.memCopy(MemoryUtil.memAddress(data) + first, MemoryUtil.memAddress(previous) + first, last - first);
        }
        uploadedCount = count;
        return Math.max(0, last - first);
    }

    @Override
    public void close() {
        closed = true;
        for (Mesh mesh : meshes) {
            mesh.close();
        }
        meshes.clear();
        glDeleteBuffers(instanceBuffer);
        if (preparedBuffer != 0) {
            glDeleteBuffers(preparedBuffer);
        }
        if (data != null) {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(previous);
            data = null;
            previous = null;
        }
    }

    final class Mesh implements AutoCloseable {
        final RenderType type;
        final int vao;
        final int vertexBuffer;
        final int indexBuffer;
        final int indices;
        private int boundInstances;

        Mesh(RenderType type, ByteBuffer vertices, int vertexCount) {
            this.type = type;
            vao = glGenVertexArrays();
            vertexBuffer = glGenBuffers();
            indexBuffer = glGenBuffers();
            indices = vertexCount / 4 * 6;
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            bindVertices();
            for (int attribute = 0; attribute < 6; attribute++) {
                glEnableVertexAttribArray(attribute);
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            IntBuffer elements = MemoryUtil.memAllocInt(indices);
            try {
                for (int vertex = 0; vertex < vertexCount; vertex += 4) {
                    int base = vertex;
                    elements.put(base).put(base + 1).put(base + 2);
                    elements.put(base + 2).put(base + 3).put(base);
                }
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements.flip(), GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(elements);
            }
            glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
            boundInstances = instanceBuffer;
            if (entityBatch) {
                glVertexAttribPointer(6, 3, GL_FLOAT, false, instanceStride, 0);
                glVertexAttribPointer(7, 2, GL_FLOAT, false, instanceStride, 16);
                glVertexAttribPointer(8, 4, GL_FLOAT, false, instanceStride, 24);
                glVertexAttribIPointer(9, 2, GL_UNSIGNED_SHORT, instanceStride, 12);
            } else {
                for (int column = 0; column < 4; column++) {
                    glVertexAttribPointer(6 + column, 4, GL_FLOAT, false, instanceStride, column * 16L);
                }
                glVertexAttribPointer(10, 4, GL_FLOAT, false, instanceStride, 64);
                glVertexAttribPointer(11, 2, GL_FLOAT, false, instanceStride, 80);
                for (int column = 0; column < 3; column++) {
                    glVertexAttribPointer(12 + column, 3, GL_FLOAT, false, instanceStride, 88 + column * 12L);
                }
                glVertexAttribPointer(15, 3, GL_FLOAT, false, instanceStride, 124);
            }
            for (int attribute = 6; attribute < (entityBatch ? 10 : 16); attribute++) {
                glEnableVertexAttribArray(attribute);
                glVertexAttribDivisor(attribute, 1);
            }
        }

        void bind() {
            glBindVertexArray(vao);
            int source = processed ? preparedBuffer : instanceBuffer;
            if (entityBatch && source != boundInstances) {
                glBindBuffer(GL_ARRAY_BUFFER, source);
                glVertexAttribPointer(6, 3, GL_FLOAT, false, instanceStride, 0);
                glVertexAttribPointer(7, 2, GL_FLOAT, false, instanceStride, 16);
                glVertexAttribPointer(8, 4, GL_FLOAT, false, instanceStride, 24);
                glVertexAttribIPointer(9, 2, GL_UNSIGNED_SHORT, instanceStride, 12);
                boundInstances = source;
            }
        }

        private void bindVertices() {
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0);
            glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, 36, 12);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 36, 16);
            glVertexAttribIPointer(3, 2, GL_SHORT, 36, 24);
            glVertexAttribIPointer(4, 2, GL_SHORT, 36, 28);
            glVertexAttribPointer(5, 3, GL_BYTE, true, 36, 32);
        }

        @Override
        public void close() {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vertexBuffer);
            glDeleteBuffers(indexBuffer);
        }
    }
}
