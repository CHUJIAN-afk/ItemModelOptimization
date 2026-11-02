package first.itemmodeloptimization.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import first.itemmodeloptimization.ItemModelOptimization;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

public final class ItemBatchRenderer {
    private final Logger logger = LoggerFactory.getLogger("ItemModelOptimization");
    private final Int2ObjectOpenHashMap<List<GpuItemBatch>> buckets = new Int2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap lightCells = new Long2IntOpenHashMap();
    private final BlockPos.MutableBlockPos lightPosition = new BlockPos.MutableBlockPos();
    private final List<Entity> remaining = new ArrayList<>();
    private final List<ItemEntity> sceneItems = new ArrayList<>();
    private final Matrix4f transform = new Matrix4f();
    private ClientLevel level;
    private Vec3 camera = Vec3.ZERO, sceneCamera = Vec3.ZERO;
    private Frustum frustum;
    private float partialTick, clock;
    private int ticks, frame, generation, program, entityProgram, preparedProgram, framebuffer;
    private double originX, originY, originZ;
    private GpuInstanceProcessor processor;
    private GpuShadowBatch shadows;
    private boolean active, failed, reused, stationary, sceneValid, sceneShadows, sceneSkip;
    private long sceneTick, builds, reuses;
    private int sceneSkyDarken, sceneRenderDistance;
    private double sceneDistanceMargin, sceneViewScale, sceneEntityDistance;
    private int sceneEntityCount, sceneInstances, sceneCollected;
    public volatile boolean sceneDirty = true;
    public boolean capturing;
    public int instances, collected, drawCalls, cacheMisses, shadowInstances, shadowDrawCalls;
    public long uploadedBytes, modelBytes;

    public void beginFrame(Camera camera, Frustum frustum, float partialTick) {
        var minecraft = Minecraft.getInstance();
        Vec3 position = camera.position();
        if (level != minecraft.level || Math.abs(position.x - originX) > 1024 || Math.abs(position.y - originY) > 1024 || Math.abs(position.z - originZ) > 1024) {
            clear();
            level = minecraft.level;
            originX = Math.floor(position.x / 256) * 256;
            originY = Math.floor(position.y / 256) * 256;
            originZ = Math.floor(position.z / 256) * 256;
        }
        this.camera = position;
        this.frustum = frustum;
        this.partialTick = partialTick;
        ticks = (int) (level.getGameTime() % 24000);
        clock = ticks + partialTick;
        frame++;
        instances = collected = drawCalls = cacheMisses = shadowDrawCalls = 0;
        uploadedBytes = modelBytes = 0;
        active = false;
        if (!failed && ItemModelOptimization.CONFIG.enabled.get() && minecraft.getMainRenderTarget().getColorTexture() instanceof GlTexture && GL.getCapabilities().OpenGL33) {
            if (program == 0) {
                try {
                    program = createProgram("instanced.vsh", "instanced.fsh");
                    entityProgram = createProgram("entity_instanced.vsh", "instanced.fsh");
                    shadows = new GpuShadowBatch(createProgram("shadow.vsh", "shadow.fsh"));
                    framebuffer = glGenFramebuffers();
                    if (GL.getCapabilities().OpenGL43) {
                        try {
                            preparedProgram = createProgram("entity_prepared.vsh", "instanced.fsh");
                            processor = new GpuInstanceProcessor();
                        } catch (Exception exception) {
                            logger.warn("Using vertex animation", exception);
                        }
                    }
                    logger.info("GPU item instancing ready: {}", glGetString(GL_RENDERER));
                } catch (Exception exception) {
                    clear();
                    failed = true;
                    logger.error("Keeping vanilla item rendering", exception);
                }
            }
            active = !failed && program != 0;
        }
        boolean shadowOption = minecraft.options.entityShadows().get();
        boolean skip = ItemModelOptimization.CONFIG.skipVisibilityChecks.get();
        reused = active && skip && sceneSkip && sceneValid && stationary && !sceneDirty && level.getSkyDarken() == sceneSkyDarken
                && sceneEntityCount == level.getEntityCount() && Entity.getViewScale() == sceneViewScale
                && minecraft.options.getEffectiveRenderDistance() == sceneRenderDistance
                && minecraft.options.entityDistanceScaling().get() == sceneEntityDistance
                && position.distanceToSqr(sceneCamera) < sceneDistanceMargin * sceneDistanceMargin && shadowOption == sceneShadows
                && !level.getChunkSource().getLightEngine().hasLightWork();
        if (reused && sceneTick != level.getGameTime()) {
            for (ItemEntity item : sceneItems) {
                EntityCache cache = ((EntityCacheAccess) item).imo$cache();
                if (item.tickCount - ticks != cache.ageOffset || !ItemStack.isSameItemSameComponents(cache.snapshot, item.getItem())) {
                    reused = false;
                    break;
                }
            }
            sceneTick = level.getGameTime();
        }
        if (reused) {
            instances = sceneInstances;
            collected = sceneCollected;
            reuses++;
        }
        if (!reused) {
            lightCells.clear();
            lightCells.defaultReturnValue(-1);
        }
        if (shadows != null) {
            shadows.count = reused ? shadows.retainedCount : 0;
            shadows.flushed = false;
            if (!reused) shadows.receivers.clear();
        }
        shadowInstances = shadows == null ? 0 : shadows.count;
        for (var bucket : buckets.values()) for (var batch : bucket) {
            batch.count = reused && batch.entityBatch ? batch.retainedCount : 0;
            batch.flushed = false;
            batch.processed = false;
            if (batch.count > 0) batch.lastFrame = frame;
        }
    }

    public Iterable<Entity> collect(Iterable<Entity> entities) {
        if (!active) return entities;
        if (reused) return remaining;
        var minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        remaining.clear();
        sceneItems.clear();
        sceneDistanceMargin = Double.POSITIVE_INFINITY;
        stationary = true;
        boolean skip = ItemModelOptimization.CONFIG.skipVisibilityChecks.get();
        for (Entity entity : entities) {
            boolean submitted = false;
            if (entity.getClass() == ItemEntity.class && !entity.hasCustomName() && !entity.shouldShowName()
                    && !entity.displayFireAnimation() && !entity.isCurrentlyGlowing() && !entity.isInvisible()
                    && dispatcher.getRenderer(entity).getClass() == ItemEntityRenderer.class) {
                ItemEntity item = (ItemEntity) entity;
                double size = item.getBoundingBox().getSize();
                if (Double.isNaN(size)) size = 1;
                sceneDistanceMargin = Math.min(sceneDistanceMargin, Math.abs(size * 64 * Entity.getViewScale() - item.position().distanceTo(camera)));
                if (skip ? !item.shouldRender(camera.x, camera.y, camera.z)
                        : !dispatcher.shouldRender(item, frustum, camera.x, camera.y, camera.z)) continue;
                ItemStack stack = item.getItem();
                EntityCache cache = ((EntityCacheAccess) item).imo$cache();
                if (cache.generation != generation || !ItemStack.isSameItemSameComponents(cache.snapshot, stack) || cache.batch != null && cache.batch.closed) {
                    cache.generation = generation;
                    cache.snapshot = stack.copy();
                    ItemStackRenderState state = new ItemStackRenderState();
                    minecraft.getItemModelResolver().updateForNonLiving(state, stack, ItemDisplayContext.GROUND, item);
                    cache.batch = find(stack, state, true);
                }
                GpuItemBatch batch = cache.batch;
                int copies = ItemClusterRenderState.getRenderedAmount(stack.getCount());
                if (batch != null && !batch.flushed && batch.count + copies <= 65536) {
                    ((ComponentVersion) stack.getComponents()).imo$track();
                    cache.ageOffset = item.tickCount - ticks;
                    sceneItems.add(item);
                    Vec3 probe = item.getLightProbePosition(partialTick);
                    lightPosition.set(Mth.floor(probe.x), Mth.floor(probe.y), Mth.floor(probe.z));
                    long cell = lightPosition.asLong();
                    int light = lightCells.get(cell);
                    if (light == -1) {
                        light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPosition), level.getBrightness(LightLayer.SKY, lightPosition));
                        lightCells.put(cell, light);
                    }
                    batch.addEntity((float) (Mth.lerp(partialTick, item.xOld, item.getX()) - originX),
                            (float) (Mth.lerp(partialTick, item.yOld, item.getY()) - originY),
                            (float) (Mth.lerp(partialTick, item.zOld, item.getZ()) - originZ), item.tickCount - (float) ticks,
                            item.bobOffs, light, batch.extensions.shouldBobAsEntity(stack), batch.extensions.shouldSpreadAsEntity(stack), copies);
                    batch.lastFrame = frame;
                    instances += copies;
                    collected++;
                    submitted = true;
                    stationary &= item.xOld == item.getX() && item.yOld == item.getY() && item.zOld == item.getZ()
                            && batch.extensions == net.neoforged.neoforge.client.extensions.common.IClientItemExtensions.DEFAULT;
                    if (minecraft.options.entityShadows().get()) shadows.add(item, partialTick, camera, originX, originY, originZ);
                }
            }
            if (!submitted) {
                remaining.add(entity);
                if (entity instanceof ItemEntity) stationary = false;
            }
        }
        for (var bucket : buckets.values()) for (var batch : bucket) if (batch.entityBatch) batch.retainedCount = batch.count;
        shadows.retainedCount = shadows.count;
        shadowInstances = shadows.count;
        builds++;
        sceneValid = true;
        sceneDirty = false;
        sceneCamera = camera;
        sceneViewScale = Entity.getViewScale();
        sceneRenderDistance = minecraft.options.getEffectiveRenderDistance();
        sceneEntityDistance = minecraft.options.entityDistanceScaling().get();
        sceneSkyDarken = level.getSkyDarken();
        sceneTick = level.getGameTime();
        sceneEntityCount = level.getEntityCount();
        sceneInstances = instances;
        sceneCollected = collected;
        sceneShadows = minecraft.options.entityShadows().get();
        sceneSkip = skip;
        return remaining;
    }

    public boolean submit(ItemStackRenderState state, PoseStack pose, int light, int overlay, int outline) {
        boolean submitted = false;
        if (active && !capturing && outline == 0 && overlay == OverlayTexture.NO_OVERLAY) {
            ItemStack stack = ((ItemStateAccess) state).imo$stack();
            GpuItemBatch batch = find(stack, state, false);
            if (batch != null && !batch.flushed && batch.count < 65536) {
                transform.set(pose.last().pose());
                transform.translateLocal((float) (camera.x - originX), (float) (camera.y - originY), (float) (camera.z - originZ));
                batch.add(transform, pose.last().normal(), 0, 0, false, light, false, 0, 0, 0);
                batch.lastFrame = frame;
                instances++;
                submitted = true;
            }
        }
        return submitted;
    }

    private GpuItemBatch find(ItemStack stack, ItemStackRenderState state, boolean entity) {
        if (stack.isEmpty() || state.isEmpty() || state.isAnimated()) return null;
        var identity = ((ItemStateAccess) state).imo$identity();
        int hash = ItemStack.hashItemAndComponents(stack);
        var bucket = buckets.computeIfAbsent(hash, key -> new ArrayList<>());
        for (var batch : bucket) if (!batch.closed && batch.entityBatch == entity && batch.model.equals(identity) && ItemStack.isSameItemSameComponents(batch.stack, stack)) return batch;
        try {
            var batch = new GpuItemBatch(stack, state, this, entity);
            bucket.add(batch);
            cacheMisses++;
            modelBytes += batch.meshBytes;
            return batch;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void flush(CameraRenderState state) {
        if (active) {
            try (GpuFrame gpuFrame = new GpuFrame(framebuffer)) {
                for (var bucket : buckets.values()) for (var batch : bucket) if (batch.count > 0 && !batch.flushed) {
                    uploadedBytes += batch.upload();
                    batch.processed = batch.entityBatch && processor != null && ItemModelOptimization.CONFIG.computeAnimation.get();
                    if (batch.processed) processor.prepare(batch, clock);
                    int drawProgram = batch.entityBatch ? batch.processed ? preparedProgram : entityProgram : program;
                    uniforms(drawProgram, state);
                    glUniform1f(glGetUniformLocation(drawProgram, "GroundScaleY"), batch.groundScaleY);
                    for (var mesh : batch.meshes) {
                        if (mesh.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
                        gpuFrame.bindTexture(0, mesh.texture);
                        mesh.bind();
                        glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                        drawCalls++;
                    }
                    batch.flushed = true;
                }
                if (shadows.count > 0) {
                    uploadedBytes += shadows.draw(state, gpuFrame);
                    shadowDrawCalls++;
                }
            }
            active = false;
            var iterator = buckets.values().iterator();
            while (iterator.hasNext()) {
                var bucket = iterator.next();
                bucket.removeIf(batch -> {
                    if (frame - batch.lastFrame > 300) { batch.close(); return true; }
                    return false;
                });
                if (bucket.isEmpty()) iterator.remove();
            }
        }
    }

    static void uniforms(int program, CameraRenderState state) {
        var renderer = ItemModelOptimization.RENDERER;
        glUseProgram(program);
        try (MemoryStack memory = MemoryStack.stackPush()) {
            glUniformMatrix4fv(glGetUniformLocation(program, "View"), false, state.viewRotationMatrix.get(memory.mallocFloat(16)));
            glUniformMatrix4fv(glGetUniformLocation(program, "Projection"), false, state.projectionMatrix.get(memory.mallocFloat(16)));
        }
        glUniform3f(glGetUniformLocation(program, "Camera"), (float) (state.pos.x - renderer.originX), (float) (state.pos.y - renderer.originY), (float) (state.pos.z - renderer.originZ));
        glUniform1f(glGetUniformLocation(program, "Clock"), renderer.clock);
        glUniform1f(glGetUniformLocation(program, "GroundScaleY"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        var fog = state.fogData;
        glUniform4f(glGetUniformLocation(program, "FogColor"), fog.color.x, fog.color.y, fog.color.z, fog.color.w);
        glUniform1f(glGetUniformLocation(program, "FogStart"), fog.environmentalStart);
        glUniform1f(glGetUniformLocation(program, "FogEnd"), fog.environmentalEnd);
        glUniform1f(glGetUniformLocation(program, "RenderFogStart"), fog.renderDistanceStart);
        glUniform1f(glGetUniformLocation(program, "RenderFogEnd"), fog.renderDistanceEnd);
        glUniform1i(glGetUniformLocation(program, "FogShape"), 0);
    }

    private int createProgram(String vertex, String fragment) throws Exception {
        int result = glCreateProgram();
        try {
            for (var entry : Map.of(GL_VERTEX_SHADER, vertex, GL_FRAGMENT_SHADER, fragment).entrySet()) {
                int shader = glCreateShader(entry.getKey());
                try (InputStream stream = getClass().getResourceAsStream("/assets/item_model_optimization/shaders/" + entry.getValue())) {
                    if (stream == null) throw new IllegalStateException("Missing shader " + entry.getValue());
                    glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    glCompileShader(shader);
                    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
                    glAttachShader(result, shader);
                } finally { glDeleteShader(shader); }
            }
            glLinkProgram(result);
            if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(result));
            int lighting = glGetUniformBlockIndex(result, "Lighting");
            if (lighting != GL_INVALID_INDEX) glUniformBlockBinding(result, lighting, 3);
            return result;
        } catch (Exception exception) { glDeleteProgram(result); throw exception; }
    }

    public void invalidate() {
        if (RenderSystem.isOnRenderThread()) clear(); else Minecraft.getInstance().execute(this::clear);
    }

    private void clear() {
        active = false;
        generation++;
        for (var bucket : buckets.values()) for (var batch : bucket) batch.close();
        buckets.clear();
        if (shadows != null) shadows.close();
        if (processor != null) processor.close();
        if (program != 0) glDeleteProgram(program);
        if (entityProgram != 0) glDeleteProgram(entityProgram);
        if (preparedProgram != 0) glDeleteProgram(preparedProgram);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        shadows = null;
        processor = null;
        program = entityProgram = preparedProgram = framebuffer = 0;
        sceneItems.clear();
        sceneValid = false;
        sceneDirty = true;
        failed = false;
    }

    public String statistics() {
        return String.format(Locale.ROOT, "%d instances (%d collected), %d instanced draws, %d shadow instances/%d shadow draws, %d new meshes, %d instance bytes/frame, %d mesh bytes/frame, %d scene builds/%d reuses", instances, collected, drawCalls, shadowInstances, shadowDrawCalls, cacheMisses, uploadedBytes, modelBytes, builds, reuses);
    }

    public interface ComponentVersion {
        int imo$version();
        void imo$track();
        boolean imo$tracked();
    }
    public interface BufferAccess { int imo$handle(); }
    public interface EntityCacheAccess { EntityCache imo$cache(); }
    public interface ItemStateAccess {
        ItemStack imo$stack();
        void imo$stack(ItemStack stack);
        List<Object> imo$identity();
    }
    public static final class EntityCache {
        private ItemStack snapshot = ItemStack.EMPTY;
        private GpuItemBatch batch;
        private int generation = -1;
        private int ageOffset;
    }
}
