package first.itemmodeloptimization.client;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

// 阴影实例共享接收面的形状与亮度查询。
final class GpuShadowBatch implements AutoCloseable {
    final Long2ObjectOpenHashMap<Receiver> receivers = new Long2ObjectOpenHashMap<>();
    private final Receiver empty = new Receiver(null, 0);
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private final RenderType type = RenderType.entityShadow(new ResourceLocation("minecraft", "textures/misc/shadow.png"));
    private final int program;
    private final int vao;
    private final int buffer;
    private final int viewUniform;
    private final int projectionUniform;
    private final int cameraUniform;
    private final int colorUniform;
    private final int fogStartUniform;
    private final int fogEndUniform;
    private final int fogColorUniform;
    private final int fogShapeUniform;
    private ByteBuffer data;
    private ByteBuffer previous;
    private int capacity;
    private int gpuCapacity;
    private int uploadedCount;
    int count;
    int retainedCount;
    boolean flushed;

    GpuShadowBatch(int program) {
        this.program = program;
        viewUniform = glGetUniformLocation(program, "View");
        projectionUniform = glGetUniformLocation(program, "Projection");
        cameraUniform = glGetUniformLocation(program, "Camera");
        colorUniform = glGetUniformLocation(program, "ColorModulator");
        fogStartUniform = glGetUniformLocation(program, "FogStart");
        fogEndUniform = glGetUniformLocation(program, "FogEnd");
        fogColorUniform = glGetUniformLocation(program, "FogColor");
        fogShapeUniform = glGetUniformLocation(program, "FogShape");
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        vao = glGenVertexArrays();
        buffer = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glVertexAttribPointer(0, 4, GL_FLOAT, false, 40, 0);
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 40, 16);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, 40, 32);
        for (int attribute = 0; attribute < 3; attribute++) {
            glEnableVertexAttribArray(attribute);
            glVertexAttribDivisor(attribute, 1);
        }
        glBindVertexArray(oldVao);
        glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
    }

    void add(ItemEntity entity, float partialTick, Vec3 camera, double originX, double originY, double originZ) {
        double distance = camera.distanceToSqr(entity.position());
        if (distance < 256 && !entity.isInvisible()) {
            float weight = (float) ((1 - distance / 256) * 0.75);
            double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
            double y = Mth.lerp(partialTick, entity.yOld, entity.getY());
            double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
            int minY = Mth.floor(y - Math.min(weight * 2, 0.15f));
            int maxY = Mth.floor(y);
            var level = entity.level();
            for (int bx = Mth.floor(x - 0.15f), maxX = Mth.floor(x + 0.15f); bx <= maxX; bx++) {
                for (int bz = Mth.floor(z - 0.15f), maxZ = Mth.floor(z + 0.15f); bz <= maxZ; bz++) {
                    for (int by = minY; by <= maxY; by++) {
                        long key = BlockPos.asLong(bx, by, bz);
                        Receiver receiver = receivers.get(key);
                        if (receiver == null) {
                            receiver = empty;
                            position.set(bx, by - 1, bz);
                            var state = level.getBlockState(position);
                            if (state.getRenderShape() != RenderShape.INVISIBLE && state.isCollisionShapeFullBlock(level, position)) {
                                var shape = state.getShape(level, position);
                                if (!shape.isEmpty()) {
                                    AABB bounds = shape.bounds().move(bx, by, bz);
                                    position.setY(by);
                                    int brightness = level.getMaxLocalRawBrightness(position);
                                    if (brightness > 3) {
                                        receiver = new Receiver(bounds, LightTexture.getBrightness(level.dimensionType(), brightness));
                                    }
                                }
                            }
                            receivers.put(key, receiver);
                        }
                        float alpha = Mth.clamp((weight - (float) (y - by) * 0.5f) * 0.5f * receiver.brightness, 0, 1);
                        if (receiver.bounds != null && alpha > 0) {
                            if (count == capacity) {
                                capacity = Math.max(64, capacity * 2);
                                data = data == null ? MemoryUtil.memAlloc(capacity * 40) : MemoryUtil.memRealloc(data, capacity * 40);
                                previous = previous == null ? MemoryUtil.memAlloc(capacity * 40) : MemoryUtil.memRealloc(previous, capacity * 40);
                            }
                            AABB bounds = receiver.bounds;
                            long address = MemoryUtil.memAddress(data) + count++ * 40L;
                            MemoryUtil.memPutFloat(address, (float) (bounds.minX - originX));
                            MemoryUtil.memPutFloat(address + 4, (float) (bounds.minY - originY));
                            MemoryUtil.memPutFloat(address + 8, (float) (bounds.minZ - originZ));
                            MemoryUtil.memPutFloat(address + 12, (float) (bounds.maxX - originX));
                            MemoryUtil.memPutFloat(address + 16, (float) (bounds.maxZ - originZ));
                            MemoryUtil.memPutFloat(address + 20, (float) (x - originX));
                            MemoryUtil.memPutFloat(address + 24, (float) (z - originZ));
                            MemoryUtil.memPutFloat(address + 28, Mth.floor(alpha * 255) / 255.0f);
                            MemoryUtil.memPutFloat(address + 32, 0.15f);
                            MemoryUtil.memPutInt(address + 36, 0);
                        }
                    }
                }
            }
        }
    }

    long draw(Matrix4f view, Matrix4f projection, Vec3 camera, double originX, double originY, double originZ) {
        long uploaded = 0;
        if (count > 0 && !flushed) {
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            boolean changed = gpuCapacity < capacity || uploadedCount != count
                    || data.slice(0, count * 40).mismatch(previous.slice(0, count * 40)) >= 0;
            if (gpuCapacity < capacity) {
                glBufferData(GL_ARRAY_BUFFER, capacity * 40L, GL_DYNAMIC_DRAW);
                gpuCapacity = capacity;
            }
            if (changed) {
                glBufferSubData(GL_ARRAY_BUFFER, 0, data.slice(0, count * 40));
                MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(previous), count * 40L);
                uploaded = count * 40L;
            }
            uploadedCount = count;
            type.setupRenderState();
            try (MemoryStack memory = MemoryStack.stackPush()) {
                ShaderInstance vanilla = RenderSystem.getShader();
                if (vanilla != null) {
                    vanilla.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
                    vanilla.apply();
                }
                glUseProgram(program);
                glUniformMatrix4fv(viewUniform, false, view.get(memory.mallocFloat(16)));
                glUniformMatrix4fv(projectionUniform, false, projection.get(memory.mallocFloat(16)));
                glUniform3f(cameraUniform, (float) (camera.x - originX), (float) (camera.y - originY), (float) (camera.z - originZ));
                float[] color = RenderSystem.getShaderColor();
                glUniform4f(colorUniform, color[0], color[1], color[2], color[3]);
                float[] fog = RenderSystem.getShaderFogColor();
                glUniform4f(fogColorUniform, fog[0], fog[1], fog[2], fog[3]);
                glUniform1f(fogStartUniform, RenderSystem.getShaderFogStart());
                glUniform1f(fogEndUniform, RenderSystem.getShaderFogEnd());
                glUniform1i(fogShapeUniform, RenderSystem.getShaderFogShape().getIndex());
                glBindVertexArray(vao);
                glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
                if (vanilla != null) vanilla.clear();
            } finally {
                type.clearRenderState();
            }
            flushed = true;
        }
        return uploaded;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(buffer);
        if (data != null) {
            MemoryUtil.memFree(data);
            MemoryUtil.memFree(previous);
            data = null;
            previous = null;
        }
        receivers.clear();
    }

    private record Receiver(AABB bounds, float brightness) {
    }
}
